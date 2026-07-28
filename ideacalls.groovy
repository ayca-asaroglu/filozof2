package Filozof.chat

import com.onresolve.scriptrunner.runner.rest.common.CustomEndpointDelegate
import groovy.transform.BaseScript
import javax.ws.rs.core.MultivaluedMap
import javax.ws.rs.core.Response
import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.bc.issue.search.SearchService
import com.atlassian.jira.web.bean.PagerFilter
import groovy.json.JsonOutput

@BaseScript CustomEndpointDelegate delegate

fibarprIdeaCalls(
  httpMethod: "GET"
) { MultivaluedMap qp ->

  // Varsayılan custom field konfigürasyonu (ön yüz parametresi olmadan çalışır)
  final String DEFAULT_THREAD_FIELD = "customfield_19807" // thread id alanı
  final List<String> DEFAULT_CUSTOM_FIELDS = [
    "customfield_19801",
    "customfield_19802",
    "customfield_19803",
    "customfield_10427",
    "customfield_19804",
    "customfield_19805",
    "customfield_19806",
    // Talep Tipi (cascade) + Yasal Zorunluluk açıklama/son tarih + Denetim Bulgusu kodu alanları
    "customfield_10400",
    "customfield_10406",
    "customfield_10405",
    "customfield_10407",
    // Business Score cascade select list alanları (approve.groovy FIELD_MAP ile aynı)
    "customfield_20209",
    "customfield_20210",
    "customfield_20211",
    "customfield_20212",
    "customfield_20213",
    "customfield_20214",
    "customfield_20215",
    "customfield_20216",
    "customfield_20217",
  ]

  def user = ComponentAccessor.jiraAuthenticationContext.loggedInUser
  if (!user) {
    return Response.status(401).entity([ok:false, error:"Unauthorized"]).build()
  }
  def adminUser = ComponentAccessor.getUserManager().getUserByName("admin")
  def loggedUserName = user.getUsername()
  def jqlQueryParser = ComponentAccessor.getComponent(com.atlassian.jira.jql.parser.JqlQueryParser)
  SearchService searchService = ComponentAccessor.getComponent(SearchService)

  def customFieldManager = ComponentAccessor.customFieldManager

  def resolveCf = { String key ->
    if (!key) return null
    def cf = customFieldManager.getCustomFieldObject(key)
    if (cf) return cf
    def byName = customFieldManager.getCustomFieldObjectsByName(key)
    return (byName && !byName.isEmpty()) ? byName[0] : null
  }

  def cfKeys = []
  ["cf1","cf2","cf3","cf4","cf5","cf6","cf7"].each { p ->
    def v = qp.getFirst(p)
    if (v) cfKeys << v.toString()
  }
  if (cfKeys.isEmpty()) {
    cfKeys = DEFAULT_CUSTOM_FIELDS.findAll { it }
  }

  def threadFieldKey = qp.getFirst("threadField")?.toString()
  if (!threadFieldKey) {
    threadFieldKey = DEFAULT_THREAD_FIELD
  }

  // Parametreler
  int offset = (qp.getFirst("offset") ?: "0") as int
  int limit = (qp.getFirst("limit") ?: "50") as int
  String issueType = (qp.getFirst("issueType") ?: "Fikir").toString()

  // JQL: login olan kullanıcının oluşturduğu "Fikir" tipindeki kayıtlar
  String jql = "issuetype = \"${issueType}\" AND reporter = \"${loggedUserName}\" ORDER BY updated DESC"

  def query = jqlQueryParser.parseQuery(jql)
  def searchResults = searchService.search(adminUser, query, PagerFilter.getUnlimitedFilter())

  def issues = searchResults.results ?: []
  def total = issues.size()
  def sliced = issues.drop(offset).take(limit)

  def items = sliced.collect { i ->
    def desc = i.description?.toString()
    def threadIdVal = null
    if (threadFieldKey) {
      def cf = resolveCf(threadFieldKey)
      threadIdVal = cf ? i.getCustomFieldValue(cf) : null
    }

    def customFields = cfKeys.collect { key ->
      def cf = resolveCf(key)
      def val = cf ? i.getCustomFieldValue(cf) : null
      boolean isCascade = cf?.customFieldType?.key?.toLowerCase()?.contains("cascadingselect")

      def out
      if (isCascade && val instanceof Map) {
        // Cascading select değeri Jira'da Map<String, Option> olarak gelir: null anahtarı
        // ebeveyn (parent), "1" anahtarı çocuk (child) option'ını taşır. Bunu frontend'in
        // beklediği {value, child:{value}} şekline dönüştürüyoruz; aksi halde JSON.toString()
        // ile düz metne çevrilip select'teki hiçbir option ile eşleşmeyen bir string olurdu.
        def parentOpt = val[null]
        def childOpt = val["1"]
        out = [
          value: parentOpt?.value,
          id   : parentOpt?.optionId?.toString(),
          child: childOpt ? [value: childOpt.value, id: childOpt.optionId?.toString()] : null
        ]
      } else if (val instanceof Collection) {
        out = val.collect { it?.toString() }.join(", ")
      } else if (val instanceof Map) {
        out = JsonOutput.toJson(val)
      } else {
        out = (val != null ? val.toString() : "")
      }

      return [
        id: key,
        name: (cf?.name ?: key),
        value: out
      ]
    }
    [
      key: i.key,
      id: i.id?.toString(),
      summary: i.summary,
      description: desc,
      status: [ name: i.status?.name ],
      created: i.created?.toString(),
      updated: i.updated?.toString(),
      thread_id: (threadIdVal != null ? threadIdVal.toString() : null),
      customFields: customFields    ]
  }

  return Response.ok([
    ok: true,
    items: items,
    total: total,
    offset: offset,
    limit: limit,
    has_more: (offset + items.size()) < total
  ]).build()
}


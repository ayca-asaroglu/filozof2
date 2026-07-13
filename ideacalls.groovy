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
      def out = val
      if (val instanceof Collection) out = val.collect { it?.toString() }.join(", ")
      else if (val instanceof Map) out = JsonOutput.toJson(val)
      return [
        id: key,
        name: (cf?.name ?: key),
        value: (out != null ? out.toString() : "")
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


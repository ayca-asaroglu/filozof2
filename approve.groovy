package Filozof.chat

import com.onresolve.scriptrunner.runner.rest.common.CustomEndpointDelegate
import groovy.transform.BaseScript
import javax.ws.rs.core.MultivaluedMap
import javax.ws.rs.core.Response
import com.atlassian.jira.component.ComponentAccessor
import groovy.json.JsonSlurper
import groovy.json.JsonOutput
import java.util.Base64
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import javax.servlet.http.HttpServletRequest
import java.nio.charset.StandardCharsets
import java.nio.charset.Charset

@BaseScript CustomEndpointDelegate delegate

fibarprIdeaApprove(
  httpMethod: "POST"
) { MultivaluedMap qp, String body, HttpServletRequest request ->

  // ===== Konfigürasyon (servis içinde) =====
  final String APPROVE_TRANSITION_ID = "11" // actionId (workflow transition id)
  final String APPROVE_TRANSITION_NAME = ""  // Seçenek B’de kullanılmıyor

  // UI'dan gelen form anahtarlarını Jira custom field'a map eder
  final Map<String, String> FIELD_MAP = [
    talepTipi                   : "customfield_10400",
    yasalAciklama               : "customfield_10406",
    yasalTarih                  : "customfield_10405",
    bulguKodu                   : "customfield_10407",
    amac                        : "customfield_19801",
    kanallar                    : "customfield_10427",
    // stakeholders             : "customfield_10493",
    stakeholderOpinions         : "customfield_19811",
    q2                          : "customfield_10722",
    q2Detail                    : "customfield_19812",
    q3                          : "customfield_16503",
    q3Detail                    : "customfield_19813",
    q4                          : "customfield_12118",
    q4Detail                    : "customfield_19810",
    q5                          : "customfield_10927",
    q5Attachment                : "",
    q6                          : "customfield_10930",
    q6Detail                    : "customfield_19814",
    q7Code                      : "customfield_20003",
    // Business Score alanları (cascade select list)
    bs1a                        : "customfield_20209",
    bs1b                        : "customfield_20210",
    bs1cCount                   : "customfield_20211",
    bs1cDuration                : "customfield_20212",
    bs2a                        : "customfield_20213",
    bs2b                        : "customfield_20214",
    bs2c                        : "customfield_20215",
    bs3CustomerImpact           : "customfield_20216",
    bs4OperationalFinancialRisk : "customfield_20217"
  ]

  // Reference alanları için custom field mapping (issue'dan çekilecek)
  final Map<String, String> REFERENCE_FIELD_MAP = [
    amac        : "customfield_19801",
    problem     : "customfield_19802",
    cozumTipi   : "customfield_19803",
    mevcutDurum : "customfield_19804",
    hedefKitle  : "customfield_19805",
    kpi         : "customfield_19806",
    description : "description"
  ]

  // Selections için q2-q6 sorularının aiImpactFull field mapping'i
  final Map<String, Map<String, String>> SELECTIONS_MAP = [
    q2: [fieldId: "customfield_10722", aiField: "nps_required",                  detailFormKey: "q2Detail"],
    q3: [fieldId: "customfield_16503", aiField: "ui_required",                   detailFormKey: "q3Detail"],
    q4: [fieldId: "customfield_12118", aiField: "countly_required",              detailFormKey: "q4Detail"],
    q5: [fieldId: "customfield_10927", aiField: "external_integration_required", detailFormKey: ""],
    q6: [fieldId: "customfield_10930", aiField: "reporting_required",            detailFormKey: "q6Detail"]
  ]

  // Label -> form key
  final Map<String, String> LABEL_TO_FORM_KEY = [
    "Talep Tipi": "talepTipi",
    "Yasal Zorunluluk Açıklaması": "yasalAciklama",
    "Yasal Zorunluluk Son Tarih": "yasalTarih",
    "Bulgu Kodu": "bulguKodu",
    "Amaç": "amac",
    "Etkilenecek Kanallar": "kanallar",
    "Paydaşlar": "stakeholders",
    "Paydaş Görüşleri": "stakeholderOpinions",
    "Paydaş Görüşleri (JSON)": "stakeholderOpinions",
    "2. Bu talep için NPS ölçümü var mı? Varsa hangi noktalarda yapılacak?": "q2",
    "2. Bu talep için NPS ölçümü var mı? Varsa hangi noktalarda yapılacak? (Detay)": "q2Detail",
    "3. Müşteriye yansıyan ekranlarda ekleme veya bir değişiklik var mı?": "q3",
    "3. Müşteriye yansıyan ekranlarda ekleme veya bir değişiklik var mı? (Detay)": "q3Detail",
    "4. Kullanıcı davranışları Countly vs bir araçla analiz edilecek mi? Evet ise detayı": "q4",
    "4. Kullanıcı davranışları Countly vs bir araçla analiz edilecek mi? Evet ise detayı (Detay)": "q4Detail",
    "5. Dış parti bir sistemle entegrasyon isteniyor mu? Evet ise Gizlilik Taahhüdü alındı mı?": "q5",
    "5. Dış parti bir sistemle entegrasyon isteniyor mu? Evet ise Gizlilik Taahhüdü alındı mı? (Detay)": "q5Attachment",
    "6. Bu taleple ilgili raporlama/dashboard ihtiyacınız var mı? Varsa detay yazınız.": "q6",
    "6. Bu taleple ilgili raporlama/dashboard ihtiyacınız var mı? Varsa detay yazınız. (Detay)": "q6Detail",
    "7. Ekran Kodu": "q7Code"
  ]

  // ===== Auth =====
  def adminUser = ComponentAccessor.getUserManager().getUserByName("admin")
  if (!adminUser) {
    return Response.status(500).entity([ok: false, error: "admin user not found"]).build()
  }

  // ===== Body parse =====
  def payload = [:]
  try {
    payload = body ? (new JsonSlurper().parseText(body) as Map) : [:]
  } catch (e) {
    return Response.status(400).entity([
      ok: false,
      error: "Invalid JSON: ${e.message}",
      body: body?.substring(0, 500)
    ]).build()
  }

  // ===== Issue resolve =====
  def issueKey = payload.issueKey ?: payload.key
  def issueId  = payload.issueId ?: payload.issue_id
  if (!issueKey && !issueId) {
    return Response.status(400).entity([
      ok: false,
      error: "issueKey or issueId is required",
      payload: payload.keySet()
    ]).build()
  }

  def issueManager = ComponentAccessor.issueManager
  def issue = issueKey ? issueManager.getIssueByCurrentKey(issueKey.toString())
                       : issueManager.getIssueObject(issueId.toString() as Long)
  if (!issue) {
    return Response.status(404).entity([ok: false, error: "Issue not found"]).build()
  }

  def customFieldManager = ComponentAccessor.customFieldManager
  def optionsManager = ComponentAccessor.optionsManager

  def resolveCf = { String key ->
    if (!key) return null
    def cf = customFieldManager.getCustomFieldObject(key)
    if (cf) return cf
    def byName = customFieldManager.getCustomFieldObjectsByName(key)
    return (byName && !byName.isEmpty()) ? byName[0] : null
  }

  def formatStakeholderOpinions = { Object raw ->
    if (raw == null) return null
    def data = raw
    if (raw instanceof String) {
      def s = raw.toString().trim()
      if (!s) return ""
      try {
        data = new JsonSlurper().parseText(s)
      } catch (e) {
        return s.replaceAll(/\s*\n\s*/, ", ").replaceAll(/\s*,\s*/, ", ").trim()
      }
    }
    if (data instanceof Map) {
      def parts = data.collect { k, v ->
        def key = k?.toString()?.trim()
        if (!key) return null

        def unitLines = [key]

        if (v instanceof Map) {
          def owners = v.owners
          def text = v.text

          if (owners instanceof Collection && owners) {
            def ownerNames = owners.collect { owner ->
              if (owner instanceof Map) {
                return (owner.displayName ?: owner.name ?: owner.key ?: "")
              }
              return owner?.toString()?.trim() ?: ""
            }.findAll { it }
            if (ownerNames) {
              unitLines << "Görüş Sahipleri: " + ownerNames.join(", ")
            }
          }

          def textStr = text?.toString()?.trim()
          if (textStr) {
            unitLines << "Görüş: " + textStr
          }
        } else {
          def val = v?.toString()?.trim()
          if (val) {
            unitLines << val
          }
        }

        return unitLines.join(" - ")
      }.findAll { it && it.trim() }
      return parts.join("; ")
    }
    if (data instanceof Collection) {
      def parts = data.collect { it?.toString()?.trim() }.findAll { it }
      return parts.join(", ")
    }
    return raw.toString()
  }

  def normalizeText = { Object raw ->
    def s = raw?.toString()
    if (s == null) return ""
    return s.trim()
  }

  def coerceCascadeOptionText = { Object raw ->
    if (raw == null) return ""

    def decodeByteList = { Collection nums ->
      if (!nums) return ""
      try {
        byte[] bytes = new byte[nums.size()]
        int idx = 0
        nums.each { n ->
          bytes[idx++] = ((Number) n).byteValue()
        }

        int zeroAtOdd = 0
        for (int i = 1; i < bytes.length; i += 2) {
          if (bytes[i] == 0) zeroAtOdd++
        }
        boolean looksUtf16Le = (bytes.length % 2 == 0) && (zeroAtOdd > 0) && (zeroAtOdd >= (bytes.length / 4))

        if (looksUtf16Le) {
          def utf16 = new String(bytes, StandardCharsets.UTF_16LE).trim()
          if (utf16 && !utf16.contains("\uFFFD")) return utf16
        }

        def utf8 = new String(bytes, StandardCharsets.UTF_8).trim()
        if (utf8 && !utf8.contains("\uFFFD")) return utf8

        def cp1254 = new String(bytes, Charset.forName("windows-1254")).trim()
        if (cp1254) return cp1254

        return new String(bytes, StandardCharsets.UTF_16LE).trim()
      } catch (ignored) {
        return ""
      }
    }

    def candidate = raw
    if (raw instanceof Map) {
      candidate = raw.value ?: raw.name ?: raw.label ?: raw
    }

    if (candidate instanceof Collection) {
      def list = (candidate as Collection).toList()
      boolean allNumbers = !list.isEmpty() && list.every { it instanceof Number }
      if (allNumbers) {
        def decoded = decodeByteList(list)
        if (decoded) return decoded
        return normalizeText(list.join(""))
      }
      return normalizeText(list.join(", "))
    }

    if (candidate instanceof CharSequence) {
      def s = normalizeText(candidate)
      if (!s) return ""

      def numericCsv = s
      if (numericCsv.startsWith("[") && numericCsv.endsWith("]")) {
        numericCsv = numericCsv.substring(1, numericCsv.length() - 1).trim()
      }

      // Handles values serialized as numeric byte lists: "84, 0, 105, ..."
      if (numericCsv ==~ /^-?\d+(\s*,\s*-?\d+)+$/) {
        try {
          def parts = numericCsv.split(/\s*,\s*/)
          def nums = parts.collect { Integer.parseInt(it) }
          def decoded = decodeByteList(nums)
          if (decoded) return decoded
        } catch (ignored) {
          // Fall through and return original string
        }
      }

      return s
    }

    if (candidate.getClass().isArray()) {
      def arr = (candidate as Object[]).toList()
      return normalizeText(arr.join(", "))
    }

    return normalizeText(candidate)
  }

  def normalizeMultiRawValues = { Object raw ->
    if (raw == null) return []
    if (raw instanceof Collection) {
      return raw.collect { normalizeText(it) }.findAll { it }
    }
    if (raw instanceof Map) {
      def vals = []
      if (raw.values instanceof Collection) vals.addAll((Collection) raw.values)
      else if (raw.value instanceof Collection) vals.addAll((Collection) raw.value)
      else if (raw.value != null) vals << raw.value
      else if (raw.name != null) vals << raw.name
      return vals.collect { normalizeText(it) }.findAll { it }
    }

    def str = normalizeText(raw)
    if (!str) return []
    if (str.startsWith("[") && str.endsWith("]")) {
      str = str.substring(1, str.length() - 1)
    }
    return str
      .split(/\s*[,;\n]\s*/)
      .collect { normalizeText(it) }
      .findAll { it }
  }

  def firstAnswerValueByLabel = { List answersList, Collection<String> labels ->
    if (!(answersList instanceof Collection) || !labels) return ""
    def wanted = labels.collect { it?.toString()?.trim() }.findAll { it } as Set
    for (def a : answersList) {
      if (!(a instanceof Map)) continue
      def label = a.label?.toString()?.trim()
      if (!label || !wanted.contains(label)) continue
      def value = normalizeText(a.value)
      if (value) return value
    }
    return ""
  }

  // ===== Form & answers =====
  def form = (payload.form instanceof Map) ? (payload.form as Map) : [:]
  def answers = (payload.answers instanceof List) ? (payload.answers as List) : []

  if (form.isEmpty() && answers) {
    answers.each { a ->
      def label = a?.label?.toString()
      def value = a?.value?.toString()
      def fk = label ? LABEL_TO_FORM_KEY[label] : null
      if (fk && value != null && value.trim()) {
        form[fk] = value.trim()
      }
    }
  }

  if (form.containsKey("stakeholderOpinions")) {
    form["stakeholderOpinions"] = formatStakeholderOpinions(form["stakeholderOpinions"])
  }

  // Bazı akışlarda amaç/kanallar yalnızca answers içinde geliyor
  if (!normalizeText(form["amac"])) {
    def amacFromAnswers = firstAnswerValueByLabel(answers, ["Amaç"])
    if (amacFromAnswers) form["amac"] = amacFromAnswers
  }
  if (!normalizeText(form["kanallar"])) {
    def channelsFromAnswers = firstAnswerValueByLabel(answers, ["Etkilenecek Kanallar"])
    if (channelsFromAnswers) form["kanallar"] = channelsFromAnswers
  }

  // ===== Cascade select list alanları =====
  final Set<String> CASCADE_FIELDS = [
    "customfield_10400",
    "customfield_20209", "customfield_20210", "customfield_20211", "customfield_20212",
    "customfield_20213", "customfield_20214", "customfield_20215", "customfield_20216",
    "customfield_20217"
  ] as Set

  // ===== Build fields =====
  def fields = [:]

  FIELD_MAP.each { formKey, cfKey ->
    if (!cfKey) return
    def v = form[formKey]
    if (v == null) return

    // Cascade select için map yapısını koru
    if (CASCADE_FIELDS.contains(cfKey)) {
      if (v instanceof Map) {
        def parentVal = coerceCascadeOptionText(v.parent?.value ?: v.parent)
        def childVal  = coerceCascadeOptionText(v.child?.value  ?: v.child)
        // Frontend, seçilen option'ın Jira option ID'sini de gönderirse (cascade.groovy artık
        // bunları döndürüyor), metin karşılaştırmasından önce ID ile kesin eşleştirme yapılabilsin.
        def parentId = v.parentId?.toString()?.trim() ?: null
        def childId  = v.childId?.toString()?.trim() ?: null
        fields[cfKey] = [
          parent  : parentVal,
          child   : childVal,
          parentId: parentId,
          childId : childId
        ]
      } else {
        def s = v.toString().trim()
        if (s) {
          fields[cfKey] = [child: s]
        }
      }
      return
    }

    def str = v.toString().trim()
    if (!str) return
    fields[cfKey] = str
  }

  // Bir alanın date/datetime tipinde olup olmadığını KESİN belirle. Tip anahtarı ("...:datetime")
  // beklenmedik olabileceğinden yalnızca ona güvenmiyoruz; customFieldType'ın Java sınıf hiyerarşisini
  // de yürüyoruz — Jira'nın DateCFType/DateTimeCFType (ve türevleri) sınıf adında "Date" geçer.
  // Bu sayede alan hangi tiple tanımlı olursa olsun tarih alanları yakalanır ve string olarak
  // inputParams'a gönderilip locale parse hatasına ("dd/MMM/yy h:mm a") düşmeleri engellenir.
  def isDateCustomField = { cf ->
    if (!cf) return false
    def cft = cf.customFieldType
    if (!cft) return false
    if ((cft.key ?: "").toLowerCase(Locale.ROOT).contains("date")) return true
    def c = cft.getClass()
    while (c != null) {
      if ((c.name ?: "").contains("Date")) return true
      c = c.superclass
    }
    return false
  }

  // ===== Convert select/multiselect values =====
  def missingOptions = []
  def convertForCustomField = { cf, raw ->
    def str = normalizeText(raw)
    if (!str && !(raw instanceof Collection) && !(raw instanceof Map)) return null

    def typeKey = cf?.customFieldType?.key ?: ""

    // NOT: Date/datetime custom field'ları burada ELE ALINMAZ. IssueInputParameters string'i
    // olarak gönderildiğinde Jira, değeri giriş yapan kullanıcının locale'i + tarih formatı ile
    // (ör. "dd/MMM/yy h:mm a") parse ediyor; ay adı (MMM) ve AM/PM (a) locale'e bağlı olduğundan
    // İngilizce dışı profillerde (ör. Türkçe: "Ağu", "ÖÖ") "invalid date format" hatası çıkıyordu.
    // (İngilizce profilde sunucu locale'i ile eşleştiği için sorun görünmüyordu.) Bu alanlar artık
    // fields.each döngüsünde yakalanıp gerçek bir Timestamp olarak doğrudan set ediliyor
    // (bkz. dateFieldUpdates) — string/locale parse hiç devreye girmediği için profil dili ne
    // olursa olsun sorunsuz çalışır.
    //
    // GÜVENLİK AĞI: Herhangi bir date/datetime alanı (tespit kaçağı, beklenmedik tip anahtarı vb.)
    // buraya kadar gelirse, ham ISO string'i ASLA inputParams'a gönderme — Jira onu locale formatıyla
    // parse etmeye çalışıp "invalid date format" hatası verir. null döndürüp string yolunu kapatıyoruz.
    if (isDateCustomField(cf)) return null

    def isSelect = typeKey.contains("select")
    if (!isSelect) return str

    def config = cf.getRelevantConfig(issue)
    def options = optionsManager.getOptions(config)
    if (!options) return str

    def normalizeKey = { Object val ->
      normalizeText(val)
        .toLowerCase(new Locale("tr", "TR"))
        .replace("ı", "i")
        .replace("İ", "i")
    }

    def findOpt = { val ->
      def target = normalizeKey(val)
      options.find { opt -> normalizeKey(opt?.value) == target }
    }

    if (typeKey.contains("multiselect")) {
      def vals = normalizeMultiRawValues(raw)
      if (!vals) return null
      def ids = []
      vals.each { item ->
        def opt = findOpt(item)
        if (opt) ids << opt.optionId
        else missingOptions << "${cf.name}: ${item}"
      }
      ids = ids.unique()
      return ids
    }

    def opt = findOpt(str)
    if (!opt) {
      missingOptions << "${cf.name}: ${str}"
      return null
    }
    return opt.optionId
  }

  // ===== Update issue fields =====
  def issueService = ComponentAccessor.issueService
  def inputParams = issueService.newIssueInputParameters()
  inputParams.setSkipScreenCheck(true)

  // Date/datetime alanları IssueInputParameters string'i olarak DEĞİL, gerçek Timestamp olarak
  // doğrudan set edilir (locale'e bağlı "invalid date format" hatasını tamamen ortadan kaldırmak
  // için). Burada toplanır, update sonrası uygulanır (aşağı bkz.).
  def dateFieldUpdates = []
  def parseIsoToTimestamp = { rawVal ->
    def s = normalizeText(rawVal)
    if (!s) return null
    // Frontend <input type="date"> => "yyyy-MM-dd"; datetime-local => "yyyy-MM-dd'T'HH:mm".
    // Locale'den bağımsız kalıcı desenlerle parse et; hiçbiri tutmazsa null (alanı bozma).
    for (p in ["yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd'T'HH:mm", "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy-MM-dd"]) {
      try {
        def sdf = new java.text.SimpleDateFormat(p)
        sdf.setLenient(false)
        return new java.sql.Timestamp(sdf.parse(s).time)
      } catch (ignored) {}
    }
    return null
  }

  // TEŞHİS: Her cf'nin döngüde nereye gittiğini izle. customfield_10405 dateRouted'ta çıkarsa
  // interception çalışıyor (sorun başka yerde); inputAdded'ta çıkarsa interception kaçırıyor demektir.
  def _diagDateRouted = []
  def _diagInputAdded = []

  fields.each { k, v ->
    def cf = resolveCf(k?.toString())
    if (!cf) return

    // Date/datetime alanları: string parse (locale) yerine gerçek Timestamp olarak doğrudan set
    // edilmek üzere toplanır. Boş değer alanı temizler; parse edilemeyen dolu değer atlanır.
    // Tespit hem tip anahtarını hem de Java sınıf hiyerarşisini kontrol eder (bkz. isDateCustomField).
    if (isDateCustomField(cf)) {
      _diagDateRouted << (cf.id?.toString())
      def isoStr = normalizeText(v)
      if (!isoStr) {
        dateFieldUpdates << [cf: cf, value: null]
      } else {
        def ts = parseIsoToTimestamp(isoStr)
        if (ts != null) dateFieldUpdates << [cf: cf, value: ts]
      }
      return
    }

    // Cascade select list: parent/child olarak gönder
    if (CASCADE_FIELDS.contains(k?.toString())) {
      if (v instanceof Map) {
        def parentVal = coerceCascadeOptionText(v.parent)
        def childVal  = coerceCascadeOptionText(v.child)
        def parentId  = v.parentId?.toString()?.trim() ?: null
        def childId   = v.childId?.toString()?.trim() ?: null

        if (parentVal || parentId) {
          def config = cf.getRelevantConfig(issue)
          def options = optionsManager.getOptions(config)

          def normalizeKey = { Object val ->
            normalizeText(val)
              .toLowerCase(new Locale("tr", "TR"))
              .replace("ı", "i")
              .replace("İ", "i")
          }

          // Frontend option ID gönderdiyse önce ID ile kesin eşleştir (metin normalizasyonu,
          // encoding veya boşluk farklarından etkilenmez); ID yoksa/eşleşmezse metne düş.
          def parentOpt = null
          if (parentId) {
            parentOpt = options?.find { opt -> opt?.optionId?.toString() == parentId }
          }
          if (!parentOpt && parentVal) {
            parentOpt = options?.find { opt -> normalizeKey(opt?.value) == normalizeKey(parentVal) }
          }
          if (!parentOpt) {
            missingOptions << "${cf.name}: parent=${parentVal}"
            return
          }

          if (childVal || childId) {
            // Child options can be exposed under parentOpt.childOptions depending on Jira option manager behavior.
            def childPool = parentOpt?.childOptions ?: options?.findAll { opt ->
              opt?.parentOption?.optionId == parentOpt.optionId
            }

            def childOpt = null
            if (childId) {
              childOpt = childPool?.find { opt -> opt?.optionId?.toString() == childId }
            }
            if (!childOpt && childVal) {
              childOpt = childPool?.find { opt -> normalizeKey(opt?.value) == normalizeKey(childVal) }
            }

            if (!childOpt) {
              missingOptions << "${cf.name}: child=${childVal} (parent=${parentVal})"
              return
            }

            inputParams.addCustomFieldValue(cf.idAsLong, parentOpt.optionId.toString())
            inputParams.addCustomFieldValue("${cf.id}:1", childOpt.optionId.toString())
          } else {
            inputParams.addCustomFieldValue(cf.idAsLong, parentOpt.optionId.toString())
          }
        }
      }
      
      return
    }

    def converted = convertForCustomField(cf, v)
    if (converted == null) return

    _diagInputAdded << (cf.id?.toString())
    if (converted instanceof Collection) {
      inputParams.addCustomFieldValue(cf.idAsLong, (converted.collect { it.toString() } as String[]))
    } else {
      inputParams.addCustomFieldValue(cf.idAsLong, converted.toString())
    }
  }

  if (!missingOptions.isEmpty()) {
    return Response.status(400).entity([
      ok: false,
      error: "Select list değerleri bulunamadı: ${missingOptions.join(', ')}",
      details: missingOptions,
      form: form.keySet()
    ]).build()
  }

  // ===== aiImpactFull servis çağrısı (issue update'den önce) =====
  def buildAiImpactPayload = {
    def reference = [:]
    REFERENCE_FIELD_MAP.each { key, cfId ->
      if (key == "description") {
        reference[key] = issue.description ?: ""
      } else if (cfId) {
        def cf = resolveCf(cfId)
        if (cf) {
          def val = issue.getCustomFieldValue(cf)
          reference[key] = (val != null) ? val.toString() : ""
        } else {
          reference[key] = ""
        }
      } else {
        reference[key] = ""
      }
    }

    def selections = [:]
    SELECTIONS_MAP.each { formKey, mapping ->
      def aiField = mapping.aiField
      if (!aiField) return

      def selValue = (form[formKey] ?: "").toString()
      def detailKey = mapping.detailFormKey
      def detailVal = detailKey ? (form[detailKey] ?: "").toString() : ""

      if (selValue || detailVal) {
        selections[aiField] = [
          value      : selValue,
          detailValue: detailVal
        ]
      }
    }

    return [
      issueKey  : issueKey?.toString() ?: "",
      reference : reference,
      selections: selections
    ]
  }

  def callAiImpactService = { aiPayload ->
    try {
      def baseUrl = ComponentAccessor.applicationProperties.getString("jira.baseurl") ?: ""
      def aiUrl = baseUrl + "/rest/scriptrunner/latest/custom/aiImpactFull"
      def url = new URL(aiUrl)
      def conn = url.openConnection() as HttpURLConnection
      conn.requestMethod = "POST"
      conn.setRequestProperty("Content-Type", "application/json")
      conn.setConnectTimeout(15_000)
      conn.setReadTimeout(85_000)

      conn.doOutput = true

      def jsonPayload = JsonOutput.toJson(aiPayload)
      conn.outputStream.withWriter("UTF-8") { writer ->
        writer << jsonPayload
      }
      log.warn("payload")
      log.warn(jsonPayload)

      def responseCode = conn.responseCode
      def responseText = ""
      if (responseCode >= 200 && responseCode < 300) {
        responseText = conn.inputStream.text
      } else {
        responseText = conn.errorStream?.text ?: ""
      }

      log.warn("response")
      log.warn(responseText)

      if (responseCode >= 200 && responseCode < 300) {
        log.warn("başarılı")
        log.warn([success: true, data: new JsonSlurper().parseText(responseText)])
        return [success: true, data: new JsonSlurper().parseText(responseText)]
      } else {
        String lowered = (responseText ?: "").toLowerCase(Locale.ROOT)
        boolean isTimeout = (responseCode == 408) || lowered.contains("timed out") || lowered.contains("timeout")
        return [
          success: false,
          timeout: isTimeout,
          error: "aiImpactFull service error (${responseCode}): ${responseText}"
        ]
      }
    } catch (e) {
      String msg = e?.message ?: ""
      String lowered = msg.toLowerCase(Locale.ROOT)
      boolean isTimeout = (e instanceof java.net.SocketTimeoutException) || lowered.contains("timed out") || lowered.contains("timeout")
      return [success: false, timeout: isTimeout, error: "aiImpactFull service call failed: ${msg}"]
    }
  }

  // ===== AI Tutarlılık Kontrolü (skipValidation flag'i yoksa) =====
  def skipValidation = false
  if (payload.skipValidation != null) {
    def skipVal = payload.skipValidation
    if (skipVal instanceof Boolean) {
      skipValidation = skipVal
    } else if (skipVal instanceof String) {
      skipValidation = skipVal.toLowerCase() == "true"
    } else {
      skipValidation = skipVal == true || skipVal == 1 || skipVal == "1"
    }
  }

  def aiWarnings = []
  def aiValidationSkipped = false

  if (!skipValidation) {
    try {
      def aiPayload = buildAiImpactPayload()
      def aiResult = callAiImpactService(aiPayload)

      if (!aiResult.success) {
        if (aiResult.timeout == true) {
          aiValidationSkipped = true
          log.warn("aiImpactFull timeout alındı; bu istekte AI tutarlılık kontrolü atlandı. Detay: ${aiResult.error}")
          return
        }
        return Response.status(400).entity([
          ok: false,
          error: aiResult.error
        ]).build()
      }

      def aiData = aiResult.data
      if (aiData && (aiData.status == "warning" || aiData.warnings)) {
        def warningsList = aiData.warnings ?: []
        warningsList.each { w ->
          if (w.level == "warning" || w.level == "error") {
            aiWarnings << [
              fieldId  : w.fieldId ?: "",
              impactKey: w.impactKey ?: "",
              code     : w.code ?: "",
              level    : w.level ?: "warning",
              message  : w.message ?: ""
            ]
          }
        }
      }

      if (aiWarnings && !aiWarnings.isEmpty()) {
        return Response.status(400).entity([
          ok: false,
          error: aiData?.summary ?: "AI tutarlılık kontrolü uyarıları bulundu",
          status: aiData?.status ?: "warning",
          totalWarnings: aiData?.totalWarnings ?: aiWarnings.size(),
          warnings: aiWarnings
        ]).build()
      }
    } catch (e) {
      return Response.status(400).entity([
        ok: false,
        error: "AI tutarlılık kontrolü sırasında hata: ${e.message}"
      ]).build()
    }
  }

  def updateValidation = issueService.validateUpdate(adminUser, issue.id, inputParams)
  if (!updateValidation.valid) {
    def errorMessages = []
    if (updateValidation.errorCollection?.errorMessages) {
      def errorMsgs = updateValidation.errorCollection.errorMessages
      if (errorMsgs instanceof Map) {
        errorMsgs.each { k, v ->
          errorMessages << v
        }
      } else if (errorMsgs instanceof Collection) {
        errorMessages.addAll(errorMsgs)
      } else {
        errorMessages << String.valueOf(errorMsgs)
      }
    }
    if (updateValidation.errorCollection?.errors) {
      updateValidation.errorCollection.errors.each { k, v ->
        errorMessages << "${k}: ${v}"
      }
    }
    def errorText = errorMessages ? errorMessages.join("; ") : "Update validation failed"
    // TEŞHİS: Hataya düşen her custom field'ın gerçek tipini ve tarih-alanı olarak algılanıp
    // algılanmadığını yanıta ekle. Bu bölümün yanıtta GÖRÜNMESİ, güncel kodun canlı olduğunu da
    // kanıtlar (codeVersion). Bir tarih alanı buraya kadar geldiyse detectedAsDate=false demektir
    // ve tespit mantığının neden kaçırdığını (tip anahtarı/sınıf) buradan anlarız.
    def diagFields = [:]
    try {
      updateValidation.errorCollection?.errors?.each { fid, msg ->
        def dcf = resolveCf(fid?.toString())
        def cft = dcf?.customFieldType
        diagFields[fid?.toString()] = [
          typeKey       : (cft?.key ?: "").toString(),
          typeClass     : (cft?.getClass()?.name ?: "").toString(),
          detectedAsDate: isDateCustomField(dcf)
        ]
      }
    } catch (ignored) {}
    return Response.status(400).entity([
      ok: false,
      error: errorText,
      details: updateValidation.errorCollection?.errors,
      messages: updateValidation.errorCollection?.errorMessages,
      fieldsCount: fields.size(),
      formKeys: form.keySet(),
      issueKey: issue.key?.toString(),
      codeVersion: "date-fix-v4-routediag",
      diag: diagFields,
      dateRouted: _diagDateRouted,
      inputAdded: _diagInputAdded
    ]).build()
  }

  issueService.update(adminUser, updateValidation)

  // Date/datetime alanlarını locale'den bağımsız olarak doğrudan Timestamp yaz. IssueInputParameters
  // string parse'ı devreye girmediği için profil dili (Türkçe, İngilizce, vb.) ne olursa olsun
  // "invalid date format" hatası oluşmaz. Transition'dan önce yazılıyor ki geçiş koşulları/
  // post-function'lar güncel değeri görsün.
  if (!dateFieldUpdates.isEmpty()) {
    try {
      def dateIssue = issueManager.getIssueObject(issue.id)
      dateFieldUpdates.each { du -> dateIssue.setCustomFieldValue(du.cf, du.value) }
      issueManager.updateIssue(adminUser, dateIssue,
        com.atlassian.jira.event.type.EventDispatchOption.DO_NOT_DISPATCH, false)
    } catch (e) {
      log.warn("Date custom field doğrudan güncellenemedi: ${e.message}", e)
    }
  }

  // update sonrası issue refresh
  issue = issueManager.getIssueObject(issue.id)

  // ===== Attachment (base64) =====
  def attachments = []
  def failedAttachments = []

  def payloadAttachments = payload?.attachments
  boolean hasPayloadAttachments = (payloadAttachments instanceof Collection) && !payloadAttachments.isEmpty()
  if (hasPayloadAttachments) {
    payloadAttachments.each { a ->
      if (!(a instanceof Map)) return
      def n = a.name?.toString()
      def t = a.type?.toString() ?: "application/octet-stream"
      def c = a.content?.toString()
      if (n && c) {
        attachments << [name: n, type: t, content: c]
      }
    }
  } else {
    def attachmentName    = form?.q5AttachmentName?.toString()
    def attachmentType    = form?.q5AttachmentType?.toString() ?: "application/octet-stream"
    def attachmentContent = form?.q5AttachmentContent?.toString()
    if (attachmentContent && attachmentName) {
      attachments << [name: attachmentName, type: attachmentType, content: attachmentContent]
    }
  }

  if (!attachments.isEmpty()) {
    def attachmentManager = ComponentAccessor.attachmentManager
    boolean attachmentsEnabled = true
    try {
      def attachmentConfigManager = ComponentAccessor.attachmentConfigManager
      if (attachmentConfigManager) {
        if (attachmentConfigManager.metaClass.respondsTo(attachmentConfigManager, "isAttachmentsEnabled")) {
          attachmentsEnabled = attachmentConfigManager.isAttachmentsEnabled()
        } else if (attachmentConfigManager.metaClass.respondsTo(attachmentConfigManager, "attachmentsEnabled")) {
          attachmentsEnabled = attachmentConfigManager.attachmentsEnabled()
        }
      } else if (attachmentManager?.metaClass?.respondsTo(attachmentManager, "attachmentsEnabled")) {
        attachmentsEnabled = attachmentManager.attachmentsEnabled()
      }
    } catch (ignored) {
      attachmentsEnabled = true
    }

    if (!attachmentsEnabled) {
      return Response.status(400).entity([ok: false, error: "Attachments are disabled"]).build()
    }

    def uploadAttachmentViaRest = { String issueKeyForUpload, String fileName, String mimeType, byte[] bytes ->
      def baseUrl = ComponentAccessor.applicationProperties.getString("jira.baseurl") ?: ""
      if (!baseUrl) {
        throw new RuntimeException("jira.baseurl bulunamadı")
      }

      def safeIssueKey = java.net.URLEncoder.encode(issueKeyForUpload ?: "", "UTF-8")
      def uploadUrl = new URL(baseUrl + "/rest/api/2/issue/" + safeIssueKey + "/attachments")
      def conn = (HttpURLConnection) uploadUrl.openConnection()
      conn.requestMethod = "POST"
      conn.doOutput = true

      def boundary = "----srAttach" + System.currentTimeMillis()
      conn.setRequestProperty("X-Atlassian-Token", "no-check")
      conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary)

      def cookieHeader = request?.getHeader("Cookie")
      if (cookieHeader) {
        conn.setRequestProperty("Cookie", cookieHeader)
      }

      def quotedName = (fileName ?: "attachment.bin").replace('"', '_')
      def crlf = "\r\n"
      conn.outputStream.withStream { os ->
        os.write(("--" + boundary + crlf).getBytes("UTF-8"))
        os.write(("Content-Disposition: form-data; name=\"file\"; filename=\"" + quotedName + "\"" + crlf).getBytes("UTF-8"))
        os.write(("Content-Type: " + (mimeType ?: "application/octet-stream") + crlf + crlf).getBytes("UTF-8"))
        os.write(bytes)
        os.write(crlf.getBytes("UTF-8"))
        os.write(("--" + boundary + "--" + crlf).getBytes("UTF-8"))
        os.flush()
      }

      def code = conn.responseCode
      if (code < 200 || code >= 300) {
        def errText = conn.errorStream?.getText("UTF-8") ?: ""
        throw new RuntimeException("REST attachment upload failed (" + code + "): " + errText)
      }
    }

    def buildCreateAttachmentParams = { File tmpFile, String fileName, String mimeType ->
      Class beanClass = Class.forName("com.atlassian.jira.issue.attachment.CreateAttachmentParamsBean")

      try {
        Class builderClass = Class.forName('com.atlassian.jira.issue.attachment.CreateAttachmentParamsBean$Builder')
        def builder = null

        try {
          builder = builderClass.getConstructor(File, String).newInstance(tmpFile, fileName)
        } catch (ignored) {
          builder = null
        }

        if (builder != null) {
          if (builder.metaClass.respondsTo(builder, "contentType", String)) {
            builder.contentType(mimeType)
          }
          if (builder.metaClass.respondsTo(builder, "author", adminUser?.getClass())) {
            builder.author(adminUser)
          }
          if (builder.metaClass.respondsTo(builder, "issue", issue?.getClass())) {
            builder.issue(issue)
          }
          return builder.build()
        }
      } catch (ignored) {
        // Fallback below
      }

      def ctors = beanClass.declaredConstructors.sort { it.parameterCount }
      for (ctor in ctors) {
        def argList = []
        int stringIdx = 0
        boolean unsupported = false

        for (Class pType : ctor.parameterTypes) {
          if (File.isAssignableFrom(pType)) {
            argList << tmpFile
          } else if (pType == String) {
            if (stringIdx == 0) argList << fileName
            else if (stringIdx == 1) argList << mimeType
            else argList << null
            stringIdx++
          } else if (pType.name.contains("ApplicationUser") || pType.name.contains("DelegatingApplicationUser")) {
            argList << adminUser
          } else if (pType.name.contains("MutableIssue") || pType.name.endsWith("Issue")) {
            argList << issue
          } else if (pType == Boolean.TYPE || pType == Boolean) {
            argList << false
          } else if (pType.name == "java.util.Date") {
            argList << new Date()
          } else {
            unsupported = true
            break
          }
        }

        if (unsupported) continue

        try {
          return ctor.newInstance(argList as Object[])
        } catch (ignored) {
          // next ctor
        }
      }

      throw new RuntimeException("CreateAttachmentParamsBean oluşturulamadı")
    }

    // Her dosyayı ayrı ayrı yükle: biri hata verirse (bozuk içerik, boyut limiti vs.)
    // diğerleri etkilenmesin. Başarısız olanlar failedAttachments'ta toplanıp yanıtta döner.
    attachments.each { a ->
      try {
        def b64 = a.content?.toString() ?: ""
        if (b64.startsWith("data:") && b64.contains(",")) {
          b64 = b64.substring(b64.indexOf(",") + 1)
        }

        byte[] bytes
        try {
          bytes = Base64.decoder.decode(b64)
        } catch (e) {
          throw new RuntimeException("İçerik çözümlenemedi (base64)")
        }

        File tmp = null
        try {
          def safeName = (a.name?.toString() ?: "attachment.bin").replaceAll(/[^a-zA-Z0-9._-]/, "_")
          tmp = File.createTempFile("sr_attach_", "_" + safeName)
          tmp.bytes = bytes
          def contentType = (a.type?.toString() ?: "application/octet-stream")
          try {
            def params = buildCreateAttachmentParams(tmp, a.name.toString(), contentType)
            attachmentManager.createAttachment(params)
          } catch (apiErr) {
            log.warn("AttachmentManager API path failed, REST fallback deneniyor: " + (apiErr?.message ?: "unknown"))
            uploadAttachmentViaRest(issue?.key?.toString(), a.name.toString(), contentType, bytes)
          }
        } finally {
          if (tmp && tmp.exists()) tmp.delete()
        }
      } catch (e) {
        def root = e
        while (root?.cause) {
          root = root.cause
        }
        def msg = e?.message ?: root?.message ?: "Bilinmeyen hata"
        log.error("Attachment upload failed for ${a?.name}", e)
        failedAttachments << [name: (a?.name?.toString() ?: "?"), error: msg]
      }
    }

    issue = issueManager.getIssueObject(issue.id)
  }

  // ===== Transition =====
  def transitionId = APPROVE_TRANSITION_ID
  def transitionName = APPROVE_TRANSITION_NAME // kullanılmıyor

  if (transitionId || transitionName) {
    Integer actionId = transitionId ? (transitionId as Integer) : null
    if (!actionId) {
      return Response.status(400).entity([ok: false, error: "Transition id is required (name lookup disabled)"]).build()
    }

    def transitionParams = issueService.newIssueInputParameters()
    transitionParams.setSkipScreenCheck(true)

    def transitionValidation = issueService.validateTransition(adminUser, issue.id, actionId, transitionParams)
    if (!transitionValidation.valid) {
      def errorMessages = []
      if (transitionValidation.errorCollection?.errorMessages) {
        def errorMsgs = transitionValidation.errorCollection.errorMessages
        if (errorMsgs instanceof Map) {
          errorMsgs.each { k, v ->
            errorMessages << v
          }
        } else if (errorMsgs instanceof Collection) {
          errorMessages.addAll(errorMsgs)
        } else {
          errorMessages << String.valueOf(errorMsgs)
        }
      }
      if (transitionValidation.errorCollection?.errors) {
        transitionValidation.errorCollection.errors.each { k, v ->
          errorMessages << "${k}: ${v}"
        }
      }
      def errorText = errorMessages ? errorMessages.join("; ") : "Transition validation failed"
      return Response.status(400).entity([
        ok: false,
        error: errorText,
        details: transitionValidation.errorCollection?.errors,
        messages: transitionValidation.errorCollection?.errorMessages
      ]).build()
    }

    issueService.transition(adminUser, transitionValidation)
  }

  return Response.ok([
    ok: true,
    issueKey: issue.key?.toString(),
    aiValidationSkipped: aiValidationSkipped,
    attachmentWarnings: failedAttachments
  ]).build()
}

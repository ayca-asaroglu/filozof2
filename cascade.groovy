import com.atlassian.jira.component.ComponentAccessor
import com.onresolve.scriptrunner.runner.rest.common.CustomEndpointDelegate
import groovy.json.JsonOutput
import groovy.transform.BaseScript

import javax.ws.rs.core.MultivaluedMap
import javax.ws.rs.core.Response

@BaseScript CustomEndpointDelegate delegate

getCascadingChildOptionsForFields(
    httpMethod: "GET"
) { MultivaluedMap queryParams ->

    def issueManager = ComponentAccessor.issueManager
    def customFieldManager = ComponentAccessor.customFieldManager
    def optionsManager = ComponentAccessor.optionsManager

    /*
     * Örnek çağrı:
     *
     * /rest/scriptrunner/latest/custom/getCascadingChildOptionsForFields
     *   ?issueKey=ABC-123
     *   &customFieldIds=20209,20301,20455
     *
     * Alternatif:
     *   &customFieldIds=customfield_20209,customfield_20301
     */

    String issueKey = queryParams.getFirst("issueKey") as String
    String customFieldIdsParam = queryParams.getFirst("customFieldIds") as String

    String sourceCfId = "customfield_10745"
    String defaultParentValue = "Default"

    Closure<String> normalizeCfId = { String cfId ->
        if (!cfId) {
            return null
        }

        cfId = cfId.trim()
        cfId.startsWith("customfield_") ? cfId : "customfield_${cfId}"
    }

    if (!issueKey) {
        return Response.status(400)
            .entity(JsonOutput.toJson([
                error: "issueKey parametresi zorunludur."
            ]))
            .type("application/json")
            .build()
    }

    if (!customFieldIdsParam) {
        return Response.status(400)
            .entity(JsonOutput.toJson([
                error: "customFieldIds parametresi zorunludur. Örnek: customFieldIds=20209,20301,20455"
            ]))
            .type("application/json")
            .build()
    }

    def issue = issueManager.getIssueByCurrentKey(issueKey)

    if (!issue) {
        return Response.status(404)
            .entity(JsonOutput.toJson([
                error   : "Issue bulunamadı.",
                issueKey: issueKey
            ]))
            .type("application/json")
            .build()
    }

    def sourceCf = customFieldManager.getCustomFieldObject(sourceCfId)

    if (!sourceCf) {
        return Response.status(404)
            .entity(JsonOutput.toJson([
                error                : "Source custom field bulunamadı.",
                source_customfield_id: sourceCfId
            ]))
            .type("application/json")
            .build()
    }

    String sourceValue = issue.getCustomFieldValue(sourceCf)?.toString()?.trim()

    if (!sourceValue) {
        return Response.status(404)
            .entity(JsonOutput.toJson([
                error                  : "Issue üzerindeki source alan değeri boş.",
                issueKey               : issueKey,
                source_customfield_id  : sourceCfId,
                source_customfield_name: sourceCf.name
            ]))
            .type("application/json")
            .build()
    }

    List<String> requestedCustomFieldIds = customFieldIdsParam
        .split(",")
        .collect { normalizeCfId(it) }
        .findAll { it }
        .unique()

    if (!requestedCustomFieldIds) {
        return Response.status(400)
            .entity(JsonOutput.toJson([
                error: "Geçerli custom field id bulunamadı."
            ]))
            .type("application/json")
            .build()
    }

    List<Map> fieldResults = []

    requestedCustomFieldIds.each { String targetCfId ->

        def targetCf = customFieldManager.getCustomFieldObject(targetCfId)

        if (!targetCf) {
            fieldResults << [
                customfield_id      : targetCfId,
                customfield_name    : null,
                status              : "CUSTOM_FIELD_NOT_FOUND",
                match_type          : null,
                matched_parent_option: null,
                child_options       : []
            ]

            return
        }

        def fieldConfig = targetCf.getRelevantConfig(issue)

        if (!fieldConfig) {
            fieldResults << [
                customfield_id      : targetCfId,
                customfield_name    : targetCf.name,
                status              : "FIELD_CONFIG_NOT_FOUND",
                match_type          : null,
                matched_parent_option: null,
                child_options       : []
            ]

            return
        }

        def allOptions = optionsManager.getOptions(fieldConfig)

        // Önce issue üzerindeki birim değerini parent option olarak ara.
        def matchedParentOption = allOptions.find { option ->
            option.parentOption == null &&
            !option.disabled &&
            option.value?.trim()?.equalsIgnoreCase(sourceValue)
        }

        String matchType = "SOURCE_VALUE"

        // Birim eşleşmesi yoksa "Default" parent option'ını ara.
        if (!matchedParentOption) {
            matchedParentOption = allOptions.find { option ->
                option.parentOption == null &&
                !option.disabled &&
                option.value?.trim()?.equalsIgnoreCase(defaultParentValue)
            }

            matchType = matchedParentOption ? "DEFAULT" : null
        }

        if (!matchedParentOption) {
            fieldResults << [
                customfield_id      : targetCfId,
                customfield_name    : targetCf.name,
                status              : "PARENT_AND_DEFAULT_OPTION_NOT_MATCHED",
                match_type          : null,
                matched_parent_option: null,
                child_options       : []
            ]

            return
        }

        List<String> childOptions = matchedParentOption.childOptions
            ?.findAll { childOption -> !childOption.disabled }
            ?.collect { childOption -> childOption.value as String }
            ?: []

        fieldResults << [
            customfield_id      : targetCfId,
            customfield_name    : targetCf.name,
            status              : "SUCCESS",
            match_type          : matchType,
            matched_parent_option: matchedParentOption.value,
            child_options       : childOptions
        ]
    }

    def responseBody = [
        issueKey                : issueKey,
        source_customfield_id   : sourceCfId,
        source_customfield_name : sourceCf.name,
        source_value            : sourceValue,
        default_parent_value    : defaultParentValue,
        fields                  : fieldResults
    ]

    log.warn(responseBody)

    return Response.ok(JsonOutput.toJson(responseBody))
        .type("application/json")
        .build()
}

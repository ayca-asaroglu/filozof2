import com.atlassian.jira.component.ComponentAccessor
import com.onresolve.scriptrunner.runner.rest.common.CustomEndpointDelegate
import groovy.json.JsonOutput
import groovy.transform.BaseScript

import javax.ws.rs.core.MultivaluedMap
import javax.ws.rs.core.Response

@BaseScript CustomEndpointDelegate delegate

/**
 * fibarprUserSearch — "Görüş Sahipleri" alanı için kullanıcı arama.
 *
 * Neden ayrı bir endpoint?
 * Jira'nın /rest/api/2/user/picker (ve /user/search) uç noktaları, uygulama
 * erişimi (lisansı) olmayan kullanıcıları "Browse users" izninden BAĞIMSIZ
 * olarak filtreler; bu yüzden lisanssız kişiler picker'da hiç görünmez.
 * Bu endpoint UserManager.getAllApplicationUsers() kullanarak lisanstan
 * bağımsız TÜM kullanıcıları döndürür — böylece lisanssız kişiler de görüş
 * sahibi olarak seçilebilir.
 *
 * Örnek:
 *   /rest/scriptrunner/latest/custom/fibarprUserSearch?query=ahmet
 *     &maxResults=10&includeInactive=false
 *
 * Yanıt (user/picker ile aynı şekil — frontend eşlemesi değişmesin diye):
 *   { "total": 3, "users": [ { key, name, displayName, emailAddress }, ... ] }
 */
fibarprUserSearch(
    httpMethod: "GET"
) { MultivaluedMap queryParams ->

    String query = ((queryParams.getFirst("query") as String) ?: "").trim()
    String maxResultsParam = queryParams.getFirst("maxResults") as String
    String includeInactiveParam = ((queryParams.getFirst("includeInactive") as String) ?: "").trim()

    int maxResults = 10
    if (maxResultsParam) {
        try {
            maxResults = Math.max(1, Math.min(50, maxResultsParam.trim().toInteger()))
        } catch (ignored) { /* geçersiz değer → varsayılan 10 */ }
    }
    boolean includeInactive = includeInactiveParam.equalsIgnoreCase("true") || includeInactiveParam == "1"

    // user/picker gibi en az 2 karakter iste (tüm dizini dökmeyi engelle).
    if (query.length() < 2) {
        return Response.ok(JsonOutput.toJson([total: 0, users: []]))
            .type("application/json")
            .build()
    }

    String needle = query.toLowerCase()
    def userManager = ComponentAccessor.userManager

    // Aramayı login kullanıcı yerine bir servis/admin hesabı bağlamında çalıştırma seçeneği.
    // NOT: getAllApplicationUsers() zaten izin/lisans filtresi UYGULAMAZ; lisanssız kullanıcılar
    // her kimlik doğrulanmış çağıran için döner. Bu impersonation yalnızca "çağıranın hiçbir
    // yetkisi yok" senaryosu için ek güvencedir, ekstra kullanıcı açmaz.
    // Boş bırakılırsa çağıran (login) kullanıcı bağlamı kullanılır.
    final String RUN_AS_USERNAME = ""

    def authContext = ComponentAccessor.jiraAuthenticationContext
    def originalUser = authContext.loggedInUser
    def runAsUser = RUN_AS_USERNAME ? userManager.getUserByName(RUN_AS_USERNAME) : null

    // Büyük dizinlerde çalışmayı sınırlamak için en fazla bu kadar eşleşme topla,
    // sonra sıralayıp maxResults kadarını döndür.
    int scanCap = 200
    List<Map> hits = []

    try {
        if (runAsUser) authContext.setLoggedInUser(runAsUser)

        for (def user : userManager.getAllApplicationUsers()) {
            if (!user) continue
            if (!includeInactive && !user.active) continue

            String displayName = user.displayName ?: ""
            String username = user.username ?: ""
            String email = user.emailAddress ?: ""

            boolean matched = displayName.toLowerCase().contains(needle) ||
                              username.toLowerCase().contains(needle) ||
                              email.toLowerCase().contains(needle)
            if (!matched) continue

            hits << [
                key         : user.key,
                name        : username,
                displayName : displayName,
                emailAddress: email
            ]

            if (hits.size() >= scanCap) break
        }
    } finally {
        // Login kullanıcı bağlamını her durumda geri yükle (thread pool'da sızmasın).
        if (runAsUser) authContext.setLoggedInUser(originalUser)
    }

    // Tam eşleşme > başlangıç eşleşmesi > içeren; ardından alfabetik.
    Closure<Integer> rank = { String dn ->
        String d = (dn ?: "").toLowerCase()
        if (d == needle) return 0
        if (d.startsWith(needle)) return 1
        return 2
    }
    hits.sort { a, b ->
        (rank(a.displayName as String) <=> rank(b.displayName as String)) ?:
            (a.displayName as String).compareToIgnoreCase(b.displayName as String)
    }

    List<Map> users = hits.take(maxResults)

    return Response.ok(JsonOutput.toJson([total: users.size(), users: users]))
        .type("application/json")
        .build()
}

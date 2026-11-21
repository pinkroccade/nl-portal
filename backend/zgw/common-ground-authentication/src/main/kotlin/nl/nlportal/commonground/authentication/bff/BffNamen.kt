package nl.nlportal.commonground.authentication.bff

/** Namen en paden van de BFF op één plek (de prototype-filters herhaalden ze). */
object BffNamen {
    /** Verwijst naar de lopende login in Redis; `__Host-`, HttpOnly, Secure, `SameSite=Lax` (de callback is cross-site). */
    const val FLOW_COOKIE = "__Host-nlp-flow"

    /** De sessie na het inloggen: `__Host-Http-`, HttpOnly, Secure, `SameSite=Strict` (RFC 10017, ASVS 3.3.2). */
    const val SESSIE_COOKIE = "__Host-Http-NLP"

    /** CSRF-token voor de SPA: een leesbaar cookie dat de SPA in de `X-XSRF-TOKEN`-header terugstuurt. */
    const val CSRF_COOKIE = "__Host-XSRF-TOKEN"

    const val INLOGPAD = "/oauth2/authorization/"
    const val CALLBACKPAD = "/login/oauth2/code/"
    const val UITLOGPAD = "/logout"
    const val SESSIEPAD = "/api/public/sessie"
    const val GRAPHQLPAD = "/graphql"

    /** De enige CSRF-uitzondering (de back-channel logout volgt in stap 9): de postsale-webhook met eigen handtekeningcontrole. */
    const val POSTSALE_WEBHOOK = "/api/public/payment/direct/postsale"

    /** Het veilige return-pad na het inloggen, door de pre-auth-sessie meegedragen. */
    const val RETURN_TO = "return_to"

    /** Begin van de login (voor de `auth_time`-controle van `prompt=login`). */
    const val LOGIN_START = "login_start"

    const val EXCHANGE_RETURN_TO = "nl.nlportal.bff.returnTo"
    const val EXCHANGE_UITLOG_URL = "nl.nlportal.bff.uitlogUrl"

    /** Een pad dat na het inloggen mag: relatief, één begin-slash, vaste tekenset. */
    val VEILIG_PAD = Regex("^/(?!/)[A-Za-z0-9/_.~%=&?-]*$")
}

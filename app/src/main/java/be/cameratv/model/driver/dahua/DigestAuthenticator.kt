package be.cameratv.model.driver.dahua

import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Authentification HTTP Digest (RFC 2617 / 7616) pour OkHttp.
 *
 * Utilisé à la fois comme [Authenticator] (réponse au 401) et comme [Interceptor] : le dernier
 * défi reçu est mémorisé pour signer d'emblée les requêtes suivantes, ce qui évite un aller-retour
 * 401 par appel (utile pour les instantanés rafraîchis en boucle).
 */
internal class DigestAuthenticator(
    private val username: String,
    private val password: String,
    private val cnonceGenerator: () -> String = ::randomCnonce,
) : Authenticator, Interceptor {

    private class CachedChallenge(val params: Map<String, String>, var nonceCount: Int = 0)

    private var cached: CachedChallenge? = null

    override fun authenticate(route: Route?, response: Response): Request? {
        if (response.responseCount() >= MAX_ATTEMPTS) return null
        val challenge = response.challenges()
            .firstOrNull { it.scheme.equals("Digest", ignoreCase = true) } ?: return null
        val params = challenge.authParams
            .mapNotNull { (k, v) -> if (k != null && v != null) k.lowercase() to v else null }
            .toMap()
        val nonce = params["nonce"] ?: return null

        // Même nonce déjà essayé sans succès : les identifiants sont faux, on abandonne.
        val previous = response.request.header(AUTHORIZATION)
        if (previous != null && previousNonce(previous) == nonce &&
            !params["stale"].equals("true", ignoreCase = true)
        ) return null

        val header = synchronized(this) {
            val entry = CachedChallenge(params)
            cached = entry
            buildHeader(response.request, entry)
        } ?: return null
        return response.request.newBuilder().header(AUTHORIZATION, header).build()
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.header(AUTHORIZATION) != null) return chain.proceed(request)
        val header = synchronized(this) { cached?.let { buildHeader(request, it) } }
        return chain.proceed(
            if (header == null) request else request.newBuilder().header(AUTHORIZATION, header).build()
        )
    }

    /** À appeler sous verrou : incrémente le compteur de nonce. */
    private fun buildHeader(request: Request, entry: CachedChallenge): String? {
        val p = entry.params
        val realm = p["realm"] ?: return null
        val nonce = p["nonce"] ?: return null
        val algorithm = p["algorithm"] ?: "MD5"
        val qops = p["qop"]?.split(',')?.map { it.trim().lowercase() }
        val qop = when {
            qops == null -> null
            "auth" in qops -> "auth"
            else -> return null // auth-int non supporté
        }
        val url = request.url
        val uri = url.encodedPath + (url.encodedQuery?.let { "?$it" } ?: "")
        val nc = if (qop != null) "%08x".format(++entry.nonceCount) else null
        val cnonce = if (qop != null || algorithm.endsWith("-sess", true)) cnonceGenerator() else null
        val response = digestResponse(
            algorithm = algorithm,
            username = username,
            password = password,
            realm = realm,
            nonce = nonce,
            method = request.method,
            uri = uri,
            qop = qop,
            nc = nc,
            cnonce = cnonce,
        ) ?: return null

        return buildString {
            append("Digest username=\"").append(quote(username)).append('"')
            append(", realm=\"").append(quote(realm)).append('"')
            append(", nonce=\"").append(quote(nonce)).append('"')
            append(", uri=\"").append(quote(uri)).append('"')
            append(", algorithm=").append(algorithm)
            append(", response=\"").append(response).append('"')
            if (qop != null) append(", qop=").append(qop).append(", nc=").append(nc)
            if (cnonce != null) append(", cnonce=\"").append(cnonce).append('"')
            p["opaque"]?.let { append(", opaque=\"").append(quote(it)).append('"') }
        }
    }

    companion object {
        private const val AUTHORIZATION = "Authorization"
        private const val MAX_ATTEMPTS = 3
        private val NONCE_REGEX = Regex("""nonce="([^"]*)"""")
        private val random = SecureRandom()

        private fun randomCnonce(): String =
            ByteArray(8).also(random::nextBytes).joinToString("") { "%02x".format(it) }

        private fun previousNonce(header: String): String? =
            NONCE_REGEX.find(header)?.groupValues?.get(1)

        private fun quote(value: String) = value.replace("\\", "\\\\").replace("\"", "\\\"")

        private fun Response.responseCount(): Int =
            generateSequence(this) { it.priorResponse }.count()

        /**
         * Calcul de la valeur `response` du Digest. Retourne null pour un algorithme inconnu.
         * Sans [qop] (mode historique RFC 2069), [nc] et [cnonce] sont ignorés.
         */
        fun digestResponse(
            algorithm: String,
            username: String,
            password: String,
            realm: String,
            nonce: String,
            method: String,
            uri: String,
            qop: String?,
            nc: String?,
            cnonce: String?,
        ): String? {
            val digestName = when (algorithm.uppercase().removeSuffix("-SESS")) {
                "MD5" -> "MD5"
                "SHA-256" -> "SHA-256"
                else -> return null
            }
            fun h(s: String) = MessageDigest.getInstance(digestName)
                .digest(s.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

            var ha1 = h("$username:$realm:$password")
            if (algorithm.endsWith("-sess", ignoreCase = true)) ha1 = h("$ha1:$nonce:$cnonce")
            val ha2 = h("$method:$uri")
            return if (qop == null) h("$ha1:$nonce:$ha2") else h("$ha1:$nonce:$nc:$cnonce:$qop:$ha2")
        }
    }
}

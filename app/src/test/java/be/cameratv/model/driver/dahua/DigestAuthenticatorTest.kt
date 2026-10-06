package be.cameratv.model.driver.dahua

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DigestAuthenticatorTest {

    private fun rfcResponse(qop: String?, nc: String?, cnonce: String?, algorithm: String = "MD5") =
        DigestAuthenticator.digestResponse(
            algorithm = algorithm,
            username = "Mufasa",
            password = "Circle Of Life",
            realm = "testrealm@host.com",
            nonce = "dcd98b7102dd2f0e8b11d0f600bfb0c093",
            method = "GET",
            uri = "/dir/index.html",
            qop = qop,
            nc = nc,
            cnonce = cnonce,
        )

    @Test
    fun `vecteur de l'exemple RFC 2617 section 3_5`() {
        assertEquals(
            "6629fae49393a05397450978507c4ef1",
            rfcResponse(qop = "auth", nc = "00000001", cnonce = "0a4f113b"),
        )
    }

    @Test
    fun `mode historique sans qop`() {
        // MD5(HA1:nonce:HA2), calculé indépendamment.
        assertEquals("670fd8c2df070c60b045671b8b24ff02", rfcResponse(qop = null, nc = null, cnonce = null))
    }

    @Test
    fun `algorithme inconnu`() {
        assertNull(rfcResponse("auth", "00000001", "x", algorithm = "SHA-512-256"))
    }
}

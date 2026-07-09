package dev.yaytsa.adaptermcp

import org.springframework.web.util.HtmlUtils

data class LoginFormModel(
    val actionPath: String,
    val clientId: String,
    val clientName: String,
    val redirectUri: String,
    val state: String?,
    val codeChallenge: String,
    val scope: String?,
    val errorMessage: String?,
)

object OAuthHtmlPages {
    fun securityHeaders(): org.springframework.http.HttpHeaders {
        val headers = org.springframework.http.HttpHeaders()
        headers.add("X-Frame-Options", "DENY")
        headers.add("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'")
        headers.add("X-Content-Type-Options", "nosniff")
        headers.add("Referrer-Policy", "no-referrer")
        return headers
    }

    fun invalidClient(): String =
        shell(
            "Authorization failed",
            "<p class=\"error\">Unknown client or redirect URL. Reconnect from your MCP client.</p>",
        )

    fun loginForm(model: LoginFormModel): String {
        fun esc(value: String?) = HtmlUtils.htmlEscape(value ?: "")
        val error = model.errorMessage?.let { "<p class=\"error\">${esc(it)}</p>" } ?: ""
        val form =
            """
            <p><strong>${esc(model.clientName)}</strong> requests access to your Yay-Tsa music library.</p>
            $error
            <form method="post" action="${esc(model.actionPath)}">
              <input type="hidden" name="client_id" value="${esc(model.clientId)}">
              <input type="hidden" name="redirect_uri" value="${esc(model.redirectUri)}">
              <input type="hidden" name="response_type" value="code">
              <input type="hidden" name="state" value="${esc(model.state)}">
              <input type="hidden" name="code_challenge" value="${esc(model.codeChallenge)}">
              <input type="hidden" name="code_challenge_method" value="S256">
              <input type="hidden" name="scope" value="${esc(model.scope)}">
              <label for="username">Username</label>
              <input type="text" id="username" name="username" autocomplete="username" autofocus required>
              <label for="password">Password</label>
              <input type="password" id="password" name="password" autocomplete="current-password" required>
              <button type="submit">Sign in &amp; approve</button>
            </form>
            """.trimIndent()
        return shell("Connect to Yay-Tsa", form)
    }

    private fun shell(
        title: String,
        body: String,
    ): String =
        """
        <!doctype html>
        <html lang="en">
        <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <title>${HtmlUtils.htmlEscape(title)}</title>
        <style>
        body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;background:#0f1115;color:#e6e6e6;font:16px/1.5 system-ui,sans-serif}
        main{width:min(92vw,360px);background:#181b22;border:1px solid #262b36;border-radius:12px;padding:28px}
        h1{margin:0 0 12px;font-size:20px}
        label{display:block;margin:14px 0 4px;font-size:14px;color:#9aa3b2}
        input{width:100%;box-sizing:border-box;padding:10px;border-radius:8px;border:1px solid #2c3340;background:#0f1115;color:#e6e6e6}
        button{margin-top:20px;width:100%;padding:11px;border:0;border-radius:8px;background:#4f7cff;color:#fff;font-size:15px;cursor:pointer}
        .error{color:#ff6b6b;font-size:14px}
        </style>
        </head>
        <body><main><h1>${HtmlUtils.htmlEscape(title)}</h1>$body</main></body>
        </html>
        """.trimIndent()
}

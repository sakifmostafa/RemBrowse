package com.rembrowser.rembrowse.util

import java.util.Base64

/**
 * Builds the small self-contained page shown when a navigation fails (no network, unknown host,
 * certificate problem...). Without it a failed load simply does nothing, which looks like a hang.
 */
object ErrorPages {

    fun buildHtml(failedUrl: String, title: String, message: String): String {
        val safeTitle = escapeHtml(title)
        val safeMessage = escapeHtml(message)
        val safeUrl = escapeHtml(failedUrl)
        val retry = if (UrlResolver.isWebUrl(failedUrl)) {
            """<a class="retry" href="$safeUrl">Try again</a>"""
        } else {
            ""
        }
        return """
            <!DOCTYPE html>
            <html lang="en">
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>$safeTitle</title>
            <style>
              :root { color-scheme: light dark; --bg:#ffffff; --fg:#09090b; --muted:#52525b; --line:#e4e4e7; --accent:#2563eb; --on-accent:#ffffff; }
              @media (prefers-color-scheme: dark) {
                :root { --bg:#0f0f10; --fg:#fafafa; --muted:#a1a1aa; --line:#3f3f46; --accent:#60a5fa; --on-accent:#0b1220; }
              }
              html, body { height: 100%; margin: 0; }
              body { background: var(--bg); color: var(--fg); font: 16px/1.5 system-ui, sans-serif;
                     display: flex; align-items: center; justify-content: center; }
              main { max-width: 30rem; padding: 2rem 1.5rem; }
              h1 { font-size: 1.375rem; font-weight: 600; line-height: 1.3; margin: 0 0 .5rem; }
              p { color: var(--muted); margin: 0 0 1rem; }
              .url { border-top: 1px solid var(--line); padding-top: 1rem; font-size: .875rem;
                     overflow-wrap: anywhere; }
              .retry { display: inline-block; margin-top: .5rem; padding: .625rem 1.25rem; border-radius: 999px;
                       background: var(--accent); color: var(--on-accent); font-weight: 600; text-decoration: none; }
              .retry:focus-visible { outline: 2px solid var(--fg); outline-offset: 2px; }
            </style>
            </head>
            <body>
            <main>
              <h1>$safeTitle</h1>
              <p>$safeMessage</p>
              <p class="url">$safeUrl</p>
              $retry
            </main>
            </body>
            </html>
        """.trimIndent()
    }

    fun buildDataUrl(failedUrl: String, title: String, message: String): String {
        val html = buildHtml(failedUrl, title, message)
        val encoded = Base64.getEncoder().encodeToString(html.toByteArray(Charsets.UTF_8))
        return "data:text/html;charset=utf-8;base64,$encoded"
    }

    /** The failed URL is attacker-influenced, so everything interpolated into the page is escaped. */
    fun escapeHtml(text: String): String = buildString(text.length + 16) {
        for (c in text) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(c)
            }
        }
    }
}

package com.cone.agent.code

import kotlinx.serialization.Serializable

/**
 * Domain types for 「写代码」 — the on-phone coding workspace.
 *
 * A project is just a sandboxed directory under `filesDir/code_projects/<id>/`; there is deliberately
 * no Room table for it. The files on disk ARE the state, so nothing can drift out of sync with what
 * the agent actually wrote, and the feature adds no database migration to an app already at schema 8.
 */
data class CodeProject(
    val id: String,
    val name: String,
    val template: String,
    val createdAt: Long,
    val updatedAt: Long,
    val fileCount: Int = 0,
)

/** One entry of a project's flattened file tree. [depth] drives the indent in the file sheet. */
data class CodeNode(
    val path: String,
    val name: String,
    val isDir: Boolean,
    val size: Long,
    val depth: Int,
)

/** A file open in the editor, with the on-disk text kept alongside the edit buffer to detect changes. */
data class OpenFile(
    val path: String,
    val saved: String,
    val buffer: String,
) {
    val dirty: Boolean get() = saved != buffer
    val lang: CodeLang get() = CodeLang.of(path)
}

/* ---------------- transcript ---------------- */

@Serializable
/** NOTE is the app talking (command output), not the model and not a failure. */
enum class CodeRole { USER, ASSISTANT, ERROR, NOTE }

/** A single diff line. [kind]: 0 = context, 1 = added, 2 = removed, 3 = elision marker. */
@Serializable
data class DiffLine(val kind: Int, val text: String) {
    companion object {
        const val CONTEXT = 0
        const val ADDED = 1
        const val REMOVED = 2
        const val GAP = 3
    }
}

/**
 * The result of one tool the model invoked. Rendered as a Claude-Code-style card in the transcript:
 * a verb, its target, a one-line summary, and — for writes — the diff that was applied.
 */
@Serializable
data class ToolOutcome(
    val tool: String,
    val target: String = "",
    val ok: Boolean = true,
    val summary: String = "",
    val detail: String = "",
    val added: Int = 0,
    val removed: Int = 0,
    val diff: List<DiffLine> = emptyList(),
)

/**
 * One turn of the coding conversation. An assistant turn keeps the model's **raw** text (tool blocks
 * included) plus the outcomes in call order: the renderer re-parses the text into prose/call segments
 * and zips the i-th call with the i-th outcome, so a replayed transcript looks exactly like the live
 * one without storing the rendering itself.
 */
@Serializable
data class CodeTurn(
    val role: CodeRole,
    val text: String = "",
    val results: List<ToolOutcome> = emptyList(),
    val streaming: Boolean = false,
    val tokens: Int? = null,
    /** Snapshot of the project taken just before this request ran, for 「回到这里」. */
    val checkpoint: String? = null,
)

/* ---------------- languages ---------------- */

/** Languages the highlighter and the template picker know about. */
enum class CodeLang(val label: String) {
    KOTLIN("Kotlin"),
    JAVA("Java"),
    PYTHON("Python"),
    JS("JavaScript"),
    TS("TypeScript"),
    JSON("JSON"),
    HTML("HTML"),
    XML("XML"),
    CSS("CSS"),
    MARKDOWN("Markdown"),
    SHELL("Shell"),
    C("C/C++"),
    GO("Go"),
    RUST("Rust"),
    SWIFT("Swift"),
    SQL("SQL"),
    YAML("YAML"),
    PLAIN("Text"),
    ;

    companion object {
        fun of(path: String): CodeLang = when (path.substringAfterLast('.', "").lowercase()) {
            "kt", "kts" -> KOTLIN
            "java" -> JAVA
            "py", "pyw" -> PYTHON
            "js", "jsx", "mjs", "cjs" -> JS
            "ts", "tsx" -> TS
            "json" -> JSON
            "html", "htm" -> HTML
            "xml", "svg" -> XML
            "css", "scss", "less" -> CSS
            "md", "markdown" -> MARKDOWN
            "sh", "bash", "zsh" -> SHELL
            "c", "h", "cpp", "cc", "hpp" -> C
            "go" -> GO
            "rs" -> RUST
            "swift" -> SWIFT
            "sql" -> SQL
            "yml", "yaml" -> YAML
            else -> PLAIN
        }
    }
}

/**
 * Starter projects. WEB comes first on purpose: a phone can't run a compiler or a shell, but it CAN
 * render HTML — so a web project is the one kind where you write code and see it run, right here.
 */
enum class ProjectTemplate(val id: String, val files: Map<String, String>) {
    WEB(
        "web",
        mapOf(
            "index.html" to WEB_HTML,
            "styles.css" to WEB_CSS,
            "app.js" to WEB_JS,
        ),
    ),
    PYTHON("python", mapOf("main.py" to PY_MAIN, "README.md" to PY_README)),
    NODE("node", mapOf("index.js" to NODE_INDEX, "package.json" to NODE_PKG)),
    KOTLIN("kotlin", mapOf("Main.kt" to KT_MAIN)),
    BLANK("blank", mapOf("README.md" to BLANK_README)),
    ;

    companion object {
        fun of(id: String): ProjectTemplate = entries.firstOrNull { it.id == id } ?: BLANK
    }
}

private const val WEB_HTML = """<!doctype html>
<html lang="zh">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>我的网页</title>
  <link rel="stylesheet" href="styles.css">
</head>
<body>
  <main class="card">
    <h1>你好，世界</h1>
    <p>这是在手机上写出来的网页，点下面的按钮试试。</p>
    <button id="go">点我</button>
    <p id="out"></p>
  </main>
  <script src="app.js"></script>
</body>
</html>
"""

private const val WEB_CSS = """:root {
  color-scheme: light dark;
  --accent: #6750a4;
}

body {
  margin: 0;
  min-height: 100vh;
  display: grid;
  place-items: center;
  font-family: system-ui, -apple-system, "PingFang SC", sans-serif;
  background: linear-gradient(160deg, #eef0ff, #f7f2ff);
}

.card {
  padding: 32px 28px;
  border-radius: 24px;
  background: #fff;
  box-shadow: 0 18px 50px rgba(60, 40, 120, .12);
  text-align: center;
  max-width: 320px;
}

h1 {
  margin: 0 0 8px;
  font-size: 26px;
}

button {
  margin-top: 12px;
  padding: 12px 24px;
  border: 0;
  border-radius: 999px;
  background: var(--accent);
  color: #fff;
  font-size: 16px;
}
"""

private const val WEB_JS = """const out = document.getElementById('out');
let count = 0;

document.getElementById('go').addEventListener('click', () => {
  count += 1;
  out.textContent = `已经点了 ${'$'}{count} 次`;
});
"""

private const val PY_MAIN = """def main():
    print("你好，世界")


if __name__ == "__main__":
    main()
"""

private const val PY_README = """# Python 项目

在这里描述你的项目。

> 手机上没有 Python 运行时，代码可以写、可以改、可以让 AI 重构，
> 需要运行时把文件复制到电脑上执行。
"""

private const val NODE_INDEX = """function greet(name) {
  return `你好，${'$'}{name}`;
}

console.log(greet('世界'));

module.exports = { greet };
"""

private const val NODE_PKG = """{
  "name": "my-app",
  "version": "1.0.0",
  "main": "index.js",
  "scripts": {
    "start": "node index.js"
  }
}
"""

private const val KT_MAIN = """fun main() {
    println("你好，世界")
}
"""

private const val BLANK_README = """# 新项目

空白起步。直接在下面告诉 AI 你想做什么，它会创建并编写文件。
"""

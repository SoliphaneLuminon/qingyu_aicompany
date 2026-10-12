package com.qingyu.app.modules

import java.util.concurrent.atomic.AtomicLong

/**
 * 一个够用的 JavaScript 子集解释器喵。
 *
 * 为什么不用 QuickJS / Rhino / WebView喵：
 * - QuickJS 与 Rhino 都会带来数 MB 的原生库或额外依赖，而模块系统只需要"能跑一小段逻辑"喵；
 * - WebView 要求模块代码跑在另一个进程/线程里，异步回调难以限定步数，反而更难收口喵。
 *
 * 这里实现的子集覆盖写一个聊天插件的全部需要喵：
 * 变量、函数、闭包、if/while/for、模板字符串、数组与对象、常用数组方法喵，
 * 以及宿主注入的 `qingyu.*`。没有 eval、没有原型链、没有网络与文件——喵
 * 结构上就拿不到这些东西，而不是靠检查喵。
 */
class SimpleJsEngine {

    data class Result(val value: Any?, val returnValue: String?, val error: String?)

    fun execute(
        code: String,
        bridge: JsBridge,
        input: String = "",
        timeoutMs: Long = 2_000,
        maxSteps: Int = 400_000,
    ): Result {
        return try {
            val program = Parser(Lexer(code).tokenize()).parseProgram()
            val interpreter = Interpreter(bridge, input, timeoutMs, maxSteps)
            val value = interpreter.run(program)
            Result(value, interpreter.stringify(value).takeIf { it != "undefined" }, null)
        } catch (t: Throwable) {
            Result(null, null, t.message ?: t::class.java.simpleName)
        }
    }

    // ------------------------------------------------------------------ 词法

    private enum class T { NUM, STR, IDENT, PUNCT, EOF }

    private data class Token(val type: T, val text: String, val pos: Int, val isTemplate: Boolean = false)

    private class Lexer(private val src: String) {
        private var i = 0
        private val out = ArrayList<Token>()

        fun tokenize(): List<Token> {
            while (i < src.length) {
                val c = src[i]
                when {
                    c.isWhitespace() -> i++
                    c == '/' && i + 1 < src.length && src[i + 1] == '/' -> skipLine()
                    c == '/' && i + 1 < src.length && src[i + 1] == '*' -> skipBlock()
                    c.isDigit() -> number()
                    c == '"' || c == '\'' -> string(c)
                    c == '`' -> template()
                    c.isLetter() || c == '_' || c == '$' -> ident()
                    else -> punct()
                }
            }
            out.add(Token(T.EOF, "", i))
            return out
        }

        private fun skipLine() {
            while (i < src.length && src[i] != '\n') i++
        }

        private fun skipBlock() {
            i += 2
            while (i + 1 < src.length && !(src[i] == '*' && src[i + 1] == '/')) i++
            i = (i + 2).coerceAtMost(src.length)
        }

        private fun number() {
            val start = i
            while (i < src.length && (src[i].isDigit() || src[i] == '.')) i++
            out.add(Token(T.NUM, src.substring(start, i), start))
        }

        private fun string(quote: Char) {
            val start = i
            i++
            val sb = StringBuilder()
            while (i < src.length && src[i] != quote) {
                if (src[i] == '\\' && i + 1 < src.length) {
                    i++
                    sb.append(
                        when (src[i]) {
                            'n' -> '\n'
                            't' -> '\t'
                            'r' -> '\r'
                            else -> src[i]
                        },
                    )
                } else {
                    sb.append(src[i])
                }
                i++
            }
            i++
            out.add(Token(T.STR, sb.toString(), start))
        }

        /** 模板字符串：整段作为一个 token，其中的 ${} 在解析阶段再切喵。 */
        private fun template() {
            val start = i
            i++
            val sb = StringBuilder()
            var depth = 0
            while (i < src.length) {
                val c = src[i]
                if (c == '\\' && i + 1 < src.length) {
                    i++
                    sb.append(
                        when (src[i]) {
                            'n' -> '\n'
                            't' -> '\t'
                            else -> src[i]
                        },
                    )
                    i++
                    continue
                }
                if (c == '$' && i + 1 < src.length && src[i + 1] == '{') {
                    depth++
                    sb.append("\${")
                    i += 2
                    continue
                }
                if (c == '}' && depth > 0) {
                    depth--
                    sb.append('}')
                    i++
                    continue
                }
                if (c == '`' && depth == 0) break
                sb.append(c)
                i++
            }
            i++
            out.add(Token(T.STR, sb.toString(), start, isTemplate = true))
        }

        private fun ident() {
            val start = i
            while (i < src.length && (src[i].isLetterOrDigit() || src[i] == '_' || src[i] == '$')) i++
            out.add(Token(T.IDENT, src.substring(start, i), start))
        }

        private val puncts = listOf(
            "===", "!==", "==", "!=", "<=", ">=", "&&", "||", "??", "**",
            "=>", "++", "--", "+=", "-=", "*=", "/=", "?.", "...",
            "+", "-", "*", "/", "%", "=", "<", ">", "!", "?", ":", ";", ",",
            ".", "(", ")", "{", "}", "[", "]",
        )

        private fun punct() {
            val start = i
            val match = puncts.firstOrNull { src.startsWith(it, i) }
            if (match != null) {
                i += match.length
                out.add(Token(T.PUNCT, match, start))
            } else {
                throw JsError("无法识别的字符 '${src[i]}'（第 $i 个字符）")
            }
        }
    }

    class JsError(message: String) : RuntimeException(message)

    // ------------------------------------------------------------------ 语法

    private sealed interface Node
    private data class Program(val body: List<Node>) : Node
    private data class VarDecl(val kind: String, val name: String, val init: Node?) : Node
    private data class FuncDecl(val name: String, val params: List<String>, val body: Node) : Node
    private data class ExprStmt(val expr: Node) : Node
    private data class If(val cond: Node, val then: Node, val otherwise: Node?) : Node
    private data class While(val cond: Node, val body: Node) : Node
    private data class ForOf(val name: String, val iterable: Node, val body: Node) : Node
    private data class ForClassic(val init: Node?, val cond: Node?, val update: Node?, val body: Node) : Node
    private data class Return(val value: Node?) : Node
    private data class Break(val label: String?) : Node
    private data class Continue(val label: String?) : Node
    private data class Throw(val value: Node) : Node
    private data class Try(val body: Node, val param: String?, val handler: Node) : Node
    private data class Block(val body: List<Node>) : Node
    private data class Labeled(val label: String, val body: Node) : Node
    private data class Binary(val op: String, val left: Node, val right: Node) : Node
    private data class Logical(val op: String, val left: Node, val right: Node) : Node
    private data class Unary(val op: String, val operand: Node, val prefix: Boolean) : Node
    private data class Assign(val op: String, val target: Node, val value: Node) : Node
    private data class Conditional(val cond: Node, val then: Node, val otherwise: Node) : Node
    private data class Call(val callee: Node, val args: List<Node>) : Node
    private data class Member(val obj: Node, val property: String, val computed: Node?, val optional: Boolean) : Node
    private data class New(val callee: Node, val args: List<Node>) : Node
    private data class ArrayLit(val items: List<Node>) : Node
    private data class ObjectLit(val entries: List<Pair<String, Node>>) : Node
    private data class FuncExpr(val name: String?, val params: List<String>, val body: Node) : Node
    private data class Arrow(val params: List<String>, val body: Node, val exprBody: Boolean = false) : Node
    private data class Ident(val name: String) : Node
    private data class Literal(val value: Any?) : Node
    private data class TemplateLit(val parts: List<Node>) : Node
    private data class Seq(val exprs: List<Node>) : Node

    private class Parser(private val tokens: List<Token>) {
        private var p = 0

        private fun peek(offset: Int = 0) = tokens[(p + offset).coerceAtMost(tokens.size - 1)]
        private fun at(text: String) = peek().text == text && peek().type != T.STR
        private fun eat(text: String): Boolean {
            if (at(text)) {
                p++
                return true
            }
            return false
        }

        private fun expect(text: String) {
            if (!eat(text)) throw JsError("缺少 '$text'（在第 ${peek().pos} 个字符附近）")
        }

        fun parseProgram(): Node {
            val body = ArrayList<Node>()
            while (peek().type != T.EOF) body.add(statement())
            return Program(body)
        }

        private fun statement(): Node {
            if (at(";")) {
                p++
                return Block(emptyList())
            }
            if (at("{")) return block()
            val token = peek()
            if (token.type == T.IDENT) {
                when (token.text) {
                    "let", "const", "var" -> return varDecl()
                    "function" -> return funcDecl()
                    "if" -> return ifStmt()
                    "while" -> {
                        p++
                        expect("(")
                        val cond = expression()
                        expect(")")
                        return While(cond, statement())
                    }

                    "for" -> return forStmt()
                    "return" -> {
                        p++
                        val value = if (at(";") || at("}") || peek().type == T.EOF) null else expression()
                        eat(";")
                        return Return(value)
                    }

                    "break" -> {
                        p++
                        val label = if (peek().type == T.IDENT && !at(";")) peek().text.also { p++ } else null
                        eat(";")
                        return Break(label)
                    }

                    "continue" -> {
                        p++
                        val label = if (peek().type == T.IDENT && !at(";")) peek().text.also { p++ } else null
                        eat(";")
                        return Continue(label)
                    }

                    "throw" -> {
                        p++
                        return Throw(expression())
                    }

                    "try" -> return tryStmt()
                    "switch" -> throw JsError("模块里暂不支持 switch，请改用 if")
                }
                // 标签： name: for(...喵)
                if (peek(1).type == T.PUNCT && peek(1).text == ":") {
                    val label = token.text
                    p += 2
                    return Labeled(label, statement())
                }
            }
            val expr = expression()
            eat(";")
            return ExprStmt(expr)
        }

        private fun block(): Node {
            expect("{")
            val body = ArrayList<Node>()
            while (!at("}") && peek().type != T.EOF) body.add(statement())
            expect("}")
            return Block(body)
        }

        private fun varDecl(): Node {
            val kind = peek().text
            p++
            val name = peek().text
            if (peek().type != T.IDENT) throw JsError("$kind 后面需要变量名")
            p++
            val init = if (eat("=")) expression() else null
            eat(";")
            return VarDecl(kind, name, init)
        }

        private fun funcDecl(): Node {
            p++
            val name = peek().text
            p++
            val params = paramList()
            val body = block()
            return FuncDecl(name, params, body)
        }

        private fun paramList(): List<String> {
            expect("(")
            val params = ArrayList<String>()
            while (!at(")")) {
                if (eat("...")) {
                    params.add("...${peek().text}")
                    p++
                } else {
                    params.add(peek().text)
                    p++
                }
                if (!eat(",")) break
            }
            expect(")")
            return params
        }

        private fun ifStmt(): Node {
            p++
            expect("(")
            val cond = expression()
            expect(")")
            val then = statement()
            val otherwise = if (eat("else")) statement() else null
            return If(cond, then, otherwise)
        }

        private fun forStmt(): Node {
            p++
            expect("(")
            // for (const x of arr)
            if (peek().type == T.IDENT && peek().text in listOf("let", "const", "var")) {
                val save = p
                p++
                val name = peek().text
                p++
                if (eat("of")) {
                    val iterable = expression()
                    expect(")")
                    return ForOf(name, iterable, statement())
                }
                p = save
            }
            val init = if (at(";")) null else statement()
            eat(";")
            val cond = if (at(";")) null else expression()
            expect(";")
            val update = if (at(")")) null else expression()
            expect(")")
            return ForClassic(init, cond, update, statement())
        }

        private fun tryStmt(): Node {
            p++
            val body = block()
            var param: String? = null
            var handler: Node? = null
            if (eat("catch")) {
                if (eat("(")) {
                    param = peek().text
                    p++
                    expect(")")
                }
                handler = block()
            }
            if (eat("finally")) block()
            return Try(body, param, handler ?: Block(emptyList()))
        }

        // -------- 表达式 --------

        private fun expression(): Node = sequence()

        private fun sequence(): Node {
            val first = assignment()
            if (!at(",")) return first
            val list = arrayListOf(first)
            while (eat(",")) list.add(assignment())
            return Seq(list)
        }

        private fun assignment(): Node {
            val left = conditional()
            val op = peek().text
            if (peek().type == T.PUNCT && op in setOf("=", "+=", "-=", "*=", "/=", "%=")) {
                p++
                return Assign(op, left, assignment())
            }
            return left
        }

        private fun conditional(): Node {
            val cond = nullish()
            if (eat("?")) {
                val then = assignment()
                expect(":")
                val otherwise = assignment()
                return Conditional(cond, then, otherwise)
            }
            return cond
        }

        private fun nullish(): Node = binaryLevel(0)

        private val precedence = listOf(
            listOf("??"),
            listOf("||"),
            listOf("&&"),
            listOf("===", "!==", "==", "!="),
            listOf("<=", ">=", "<", ">"),
            listOf("+", "-"),
            listOf("*", "/", "%"),
            listOf("**"),
        )

        private fun binaryLevel(level: Int): Node {
            if (level >= precedence.size) return unary()
            var left = binaryLevel(level + 1)
            while (peek().type == T.PUNCT && peek().text in precedence[level]) {
                val op = peek().text
                p++
                val right = binaryLevel(level + 1)
                left = if (op == "&&" || op == "||" || op == "??") Logical(op, left, right) else Binary(op, left, right)
            }
            return left
        }

        private fun unary(): Node {
            val token = peek()
            if (token.type == T.PUNCT) {
                when (token.text) {
                    "!", "-", "+", "++", "--", "..." -> {
                        p++
                        return Unary(token.text, unary(), true)
                    }
                }
            }
            if (token.type == T.IDENT && token.text == "new") {
                p++
                val callee = postfix(primaryOrUnary())
                return New(callee, emptyList())
            }
            if (token.type == T.IDENT && token.text in listOf("typeof", "void")) {
                p++
                return Unary(token.text, unary(), true)
            }
            return postfix(primaryOrUnary())
        }

        private fun postfix(start: Node): Node {
            var node = start
            while (true) {
                val token = peek()
                if (token.type != T.PUNCT && token.type != T.STR) return node
                when {
                    token.text == "." || token.text == "?." -> {
                        val optional = token.text == "?."
                        p++
                        val name = peek().text
                        p++
                        node = Member(node, name, null, optional)
                    }

                    token.text == "[" -> {
                        p++
                        val index = expression()
                        expect("]")
                        node = Member(node, "", index, false)
                    }

                    token.text == "(" -> {
                        p++
                        val args = ArrayList<Node>()
                        while (!at(")")) {
                            args.add(assignment())
                            if (!eat(",")) break
                        }
                        expect(")")
                        node = Call(node, args)
                    }

                    token.text == "++" || token.text == "--" -> {
                        p++
                        node = Unary(token.text, node, false)
                    }

                    else -> return node
                }
            }
        }

        private fun primaryOrUnary(): Node {
            val token = peek()
            when (token.type) {
                T.NUM -> {
                    p++
                    return Literal(token.text.toDoubleOrNull() ?: 0.0)
                }

                T.STR -> {
                    p++
                    if (token.isTemplate) {
                        return TemplateLit(parseTemplateParts(token.text))
                    }
                    return Literal(token.text)
                }

                T.IDENT -> {
                    when (token.text) {
                        "true" -> {
                            p++
                            return Literal(true)
                        }

                        "false" -> {
                            p++
                            return Literal(false)
                        }

                        "null" -> {
                            p++
                            return Literal(null)
                        }

                        "undefined" -> {
                            p++
                            return Literal(Undefined)
                        }

                        "function" -> {
                            p++
                            val name = if (peek().type == T.IDENT) peek().text.also { p++ } else null
                            val params = paramList()
                            val body = block()
                            return FuncExpr(name, params, body)
                        }
                    }
                    p++
                    // 箭头函数： x => ...  或 (a, b) => ...喵
                    if (at("=>")) {
                        p++
                        return arrowBody(listOf(token.text))
                    }
                    return Ident(token.text)
                }

                T.PUNCT -> when (token.text) {
                    "(" -> {
                        // 可能是箭头函数的参数列表或普通括号表达式喵
                        val save = p
                        runCatching {
                            p++
                            val params = ArrayList<String>()
                            var ok = true
                            while (!at(")")) {
                                if (peek().type != T.IDENT) {
                                    ok = false
                                    break
                                }
                                params.add(peek().text)
                                p++
                                if (!eat(",")) break
                            }
                            if (ok && at(")") && peek(1).text == "=>") {
                                p++
                                p++
                                return arrowBody(params)
                            }
                        }
                        p = save
                        p++
                        val inner = expression()
                        expect(")")
                        return inner
                    }

                    "[" -> {
                        p++
                        val items = ArrayList<Node>()
                        while (!at("]")) {
                            if (eat(",")) continue
                            items.add(assignment())
                            if (!eat(",")) break
                        }
                        expect("]")
                        return ArrayLit(items)
                    }

                    "{" -> {
                        p++
                        val entries = ArrayList<Pair<String, Node>>()
                        while (!at("}")) {
                            val key = when {
                                peek().type == T.STR -> peek().text.also { p++ }
                                else -> peek().text.also { p++ }
                            }
                            val value = if (eat(":")) assignment() else Ident(key)
                            entries.add(key to value)
                            if (!eat(",")) break
                        }
                        expect("}")
                        return ObjectLit(entries)
                    }

                    "!" -> {
                        p++
                        return Unary("!", unary(), true)
                    }

                    "-" -> {
                        p++
                        return Unary("-", unary(), true)
                    }

                    "+" -> {
                        p++
                        return Unary("+", unary(), true)
                    }
                }

                else -> {}
            }
            throw JsError("看不懂这里的写法：'${token.text}'")
        }

        private fun arrowBody(params: List<String>): Node {
            if (at("{")) return Arrow(params, block(), exprBody = false)
            return Arrow(params, assignment(), exprBody = true)
        }

        /** 把模板字符串切成字面量与表达式交替的序列喵。 */
        private fun parseTemplateParts(raw: String): List<Node> {
            val parts = ArrayList<Node>()
            val sb = StringBuilder()
            var i = 0
            while (i < raw.length) {
                if (raw[i] == '$' && i + 1 < raw.length && raw[i + 1] == '{') {
                    if (sb.isNotEmpty()) {
                        parts.add(Literal(sb.toString()))
                        sb.clear()
                    }
                    var depth = 1
                    i += 2
                    val exprStart = i
                    while (i < raw.length && depth > 0) {
                        when (raw[i]) {
                            '{' -> depth++
                            '}' -> depth--
                        }
                        if (depth > 0) i++
                    }
                    val exprSrc = raw.substring(exprStart, i)
                    parts.add(Parser(Lexer(exprSrc).tokenize()).parseProgram().let { program ->
                        (program as Program).body.firstOrNull()
                            ?.let { if (it is ExprStmt) it.expr else it }
                            ?: Literal("")
                    })
                    i++
                } else {
                    sb.append(raw[i])
                    i++
                }
            }
            if (sb.isNotEmpty()) parts.add(Literal(sb.toString()))
            return parts
        }
    }

    companion object {
        val Undefined = object {
            override fun toString() = "undefined"
        }
    }

    // ------------------------------------------------------------------ 执行

    private class Scope(val parent: Scope?) {
        val values = HashMap<String, Any?>()
        val constants = HashSet<String>()

        fun define(name: String, value: Any?, constant: Boolean = false) {
            values[name] = value
            if (constant) constants.add(name)
        }

        fun lookup(name: String): Pair<Scope, Boolean> {
            var scope: Scope? = this
            while (scope != null) {
                if (scope.values.containsKey(name)) return scope to true
                scope = scope.parent
            }
            return this to false
        }

        fun get(name: String): Any? {
            val (scope, found) = lookup(name)
            if (!found) throw JsError("变量 $name 没有定义")
            return scope.values[name]
        }

        fun set(name: String, value: Any?) {
            val (scope, found) = lookup(name)
            if (found && scope.constants.contains(name)) throw JsError("$name 是常量，不能重新赋值")
            if (found) scope.values[name] = value else values[name] = value
        }

        /** var 声明提升到最近的函数作用域喵。 */
        fun functionScope(): Scope {
            var scope: Scope = this
            while (scope.parent != null && !scope.isFunction) scope = scope.parent!!
            return scope
        }

        var isFunction = false
    }

    private class JsFunction(
        val name: String?,
        val params: List<String>,
        val body: Node,
        val closure: Scope,
        val exprBody: Boolean,
    )

    private class ReturnSignal(val value: Any?) : RuntimeException()
    private class BreakSignal(val label: String?) : RuntimeException()
    private class ContinueSignal(val label: String?) : RuntimeException()
    private class ThrowSignal(val value: Any?) : RuntimeException()

    private class Interpreter(
        private val bridge: JsBridge,
        private val input: String,
        private val timeoutMs: Long,
        private val maxSteps: Int,
    ) {
        private val deadline = System.currentTimeMillis() + timeoutMs
        private val steps = AtomicLong(0)
        private val global = Scope(null).apply { isFunction = true }

        init {
            installBuiltins()
        }

        fun run(program: Node): Any? {
            val scope = Scope(global).apply { isFunction = true }
            return exec(program, scope)
        }

        fun stringify(value: Any?): String = when (value) {
            null -> "null"
            Undefined -> "undefined"
            is String -> value
            is Double -> if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
            is Boolean -> value.toString()
            is JsFunction -> "[function ${value.name ?: "anonymous"}]"
            is List<*> -> "[" + value.joinToString(", ") { stringify(it) } + "]"
            is Map<*, *> -> "{" + value.entries.joinToString(", ") { "${it.key}: ${stringify(it.value)}" } + "}"
            else -> value.toString()
        }

        private fun tick() {
            val count = steps.incrementAndGet()
            if (count > maxSteps) throw JsError("模块执行了太多步，已停止（可能是死循环）")
            if (count % 512 == 0L && System.currentTimeMillis() > deadline) {
                throw JsError("模块执行超时，已停止")
            }
        }

        private fun installBuiltins() {
            global.define(
                "console",
                mutableMapOf<String, Any?>(
                    "log" to NativeFunction { args ->
                        bridge.log(args.joinToString(" ") { stringify(it) })
                        Undefined
                    },
                ),
            )

            val qingyu = mutableMapOf<String, Any?>()
            listOf("personaName", "recentMessages", "pushMessage", "getValue", "setValue", "now", "input")
                .forEach { name ->
                    qingyu[name] = NativeFunction { args ->
                        when (name) {
                            "input" -> input
                            else -> bridge.call(name, args.map { stringify(it) })
                        }
                    }
                }
            global.define("qingyu", qingyu)
            global.define("input", input)
            global.define(
                "JSON",
                mutableMapOf<String, Any?>(
                    "stringify" to NativeFunction { args -> stringify(args.firstOrNull()) },
                    "parse" to NativeFunction { args -> parseJson(stringify(args.firstOrNull())) },
                ),
            )
            global.define(
                "Math",
                mutableMapOf<String, Any?>(
                    "floor" to NativeFunction { args -> Math.floor(toNumber(args.firstOrNull())) },
                    "ceil" to NativeFunction { args -> Math.ceil(toNumber(args.firstOrNull())) },
                    "round" to NativeFunction { args -> Math.round(toNumber(args.firstOrNull())).toDouble() },
                    "abs" to NativeFunction { args -> Math.abs(toNumber(args.firstOrNull())) },
                    "max" to NativeFunction { args -> args.maxOfOrNull { toNumber(it) } ?: Double.NaN },
                    "min" to NativeFunction { args -> args.minOfOrNull { toNumber(it) } ?: Double.NaN },
                    "random" to NativeFunction { Math.random() },
                    "PI" to Math.PI,
                ),
            )
            global.define("Number", NativeFunction { args -> toNumber(args.firstOrNull()) })
            global.define("String", NativeFunction { args -> stringify(args.firstOrNull()) })
            global.define(
                "parseInt",
                NativeFunction { args ->
                    val text = stringify(args.firstOrNull())
                    text.toDoubleOrNull()
                        ?: text.filter { it.isDigit() || it == '-' }.toDoubleOrNull()
                        ?: Double.NaN
                },
            )
            global.define("isNaN", NativeFunction { args -> toNumber(args.firstOrNull()).isNaN() })
            global.define(
                "Array",
                mutableMapOf<String, Any?>(
                    "isArray" to NativeFunction { args -> args.firstOrNull() is List<*> },
                    "from" to NativeFunction { args ->
                        (args.firstOrNull() as? List<*>)?.toMutableList() ?: mutableListOf<Any?>()
                    },
                ),
            )
        }

        private fun parseJson(text: String): Any? = runCatching {
            val element = com.google.gson.JsonParser.parseString(text)
            jsonToValue(element)
        }.getOrNull()

        private fun jsonToValue(element: com.google.gson.JsonElement): Any? = when {
            element.isJsonNull -> null
            element.isJsonArray -> element.asJsonArray.map { jsonToValue(it) }.toMutableList()
            element.isJsonObject -> {
                val map = LinkedHashMap<String, Any?>()
                element.asJsonObject.entrySet().forEach { entry ->
                    map[entry.key] = jsonToValue(entry.value)
                }
                map
            }

            element.asJsonPrimitive.isBoolean -> element.asBoolean
            element.asJsonPrimitive.isNumber -> element.asDouble
            else -> element.asString
        }

        private fun exec(node: Node, scope: Scope): Any? {
            tick()
            return when (node) {
                is Program -> {
                    var last: Any? = Undefined
                    node.body.forEach { last = exec(it, scope) }
                    last
                }

                is Block -> {
                    val blockScope = Scope(scope)
                    var last: Any? = Undefined
                    node.body.forEach { last = exec(it, blockScope) }
                    last
                }

                is VarDecl -> {
                    val value = node.init?.let { exec(it, scope) } ?: Undefined
                    val target = if (node.kind == "var") scope.functionScope() else scope
                    target.define(node.name, value, constant = node.kind == "const")
                    value
                }

                is FuncDecl -> {
                    scope.define(node.name, JsFunction(node.name, node.params, node.body, scope, false))
                    Undefined
                }

                is ExprStmt -> exec(node.expr, scope)
                is If -> if (truthy(exec(node.cond, scope))) exec(node.then, scope)
                else node.otherwise?.let { exec(it, scope) } ?: Undefined

                is While -> {
                    var guard = 0
                    while (truthy(exec(node.cond, scope))) {
                        try {
                            exec(node.body, scope)
                        } catch (signal: BreakSignal) {
                            break
                        } catch (signal: ContinueSignal) {
                            // 继续下一轮喵
                        }
                        if (++guard > 1_000_000) throw JsError("循环次数过多")
                    }
                    Undefined
                }

                is ForOf -> {
                    val iterable = exec(node.iterable, scope)
                    val items = when (iterable) {
                        is List<*> -> iterable
                        is String -> iterable.map { it.toString() }
                        else -> emptyList<Any?>()
                    }
                    val loopScope = Scope(scope)
                    loopScope.define(node.name, Undefined)
                    for (item in items) {
                        loopScope.values[node.name] = item
                        try {
                            exec(node.body, loopScope)
                        } catch (signal: BreakSignal) {
                            break
                        } catch (signal: ContinueSignal) {
                            continue
                        }
                    }
                    Undefined
                }

                is ForClassic -> {
                    val loopScope = Scope(scope)
                    node.init?.let { exec(it, loopScope) }
                    var guard = 0
                    while (node.cond == null || truthy(exec(node.cond, loopScope))) {
                        try {
                            exec(node.body, loopScope)
                        } catch (signal: BreakSignal) {
                            break
                        } catch (signal: ContinueSignal) {
                            // 忽略喵
                        }
                        node.update?.let { exec(it, loopScope) }
                        if (++guard > 1_000_000) throw JsError("循环次数过多")
                    }
                    Undefined
                }

                is Return -> throw ReturnSignal(node.value?.let { exec(it, scope) })
                is Break -> throw BreakSignal(node.label)
                is Continue -> throw ContinueSignal(node.label)
                is Throw -> throw ThrowSignal(exec(node.value, scope))
                is Try -> try {
                    exec(node.body, scope)
                } catch (signal: ThrowSignal) {
                    val catchScope = Scope(scope)
                    node.param?.let { catchScope.define(it, signal.value) }
                    exec(node.handler, catchScope)
                } catch (error: JsError) {
                    // 解释器自身报的错（成员不存在、类型不对等）也要能被 JS 捕获喵，
                    // 否则插件作者没法做失败兜底，只能整段崩掉喵。
                    val catchScope = Scope(scope)
                    node.param?.let { catchScope.define(it, error.message.orEmpty()) }
                    exec(node.handler, catchScope)
                }

                is Labeled -> try {
                    exec(node.body, scope)
                } catch (signal: BreakSignal) {
                    if (signal.label != node.label) throw signal
                    Undefined
                }

                is Binary -> binary(node.op, exec(node.left, scope), exec(node.right, scope))
                is Logical -> {
                    val left = exec(node.left, scope)
                    when (node.op) {
                        "&&" -> if (truthy(left)) exec(node.right, scope) else left
                        "||" -> if (truthy(left)) left else exec(node.right, scope)
                        else -> if (left == null || left == Undefined) exec(node.right, scope) else left
                    }
                }

                is Unary -> {
                    if (!node.prefix) {
                        val old = toNumber(evalTarget(node.operand, scope))
                        val next = old + if (node.op == "++") 1.0 else -1.0
                        assignTo(node.operand, next, scope)
                        old
                    } else {
                        when (node.op) {
                            "!" -> !truthy(exec(node.operand, scope))
                            "-" -> -toNumber(exec(node.operand, scope))
                            "+" -> toNumber(exec(node.operand, scope))
                            "typeof" -> typeName(exec(node.operand, scope))
                            "void" -> Undefined
                            "..." -> exec(node.operand, scope)
                            else -> Undefined
                        }
                    }
                }

                is Assign -> {
                    val value = exec(node.value, scope)
                    val result = when (node.op) {
                        "=" -> value
                        "+=" -> plus(evalTarget(node.target, scope), value)
                        "-=" -> toNumber(evalTarget(node.target, scope)) - toNumber(value)
                        "*=" -> toNumber(evalTarget(node.target, scope)) * toNumber(value)
                        "/=" -> toNumber(evalTarget(node.target, scope)) / toNumber(value)
                        "%=" -> toNumber(evalTarget(node.target, scope)) % toNumber(value)
                        else -> value
                    }
                    assignTo(node.target, result, scope)
                    result
                }

                is Conditional -> if (truthy(exec(node.cond, scope))) exec(node.then, scope) else exec(node.otherwise, scope)
                is Call -> callFunction(exec(node.callee, scope), node.args.map { exec(it, scope) })
                is New -> exec(node.callee, scope)
                is Member -> memberGet(exec(node.obj, scope), node, scope)
                is ArrayLit -> node.items.map { exec(it, scope) }.toMutableList()
                is ObjectLit -> {
                    val map = LinkedHashMap<String, Any?>()
                    node.entries.forEach { entry -> map[entry.first] = exec(entry.second, scope) }
                    map
                }
                is FuncExpr -> JsFunction(node.name, node.params, node.body, scope, false)
                is Arrow -> JsFunction(null, node.params, node.body, scope, node.exprBody)
                is Ident -> scope.get(node.name)
                is Literal -> node.value
                is TemplateLit -> node.parts.joinToString("") { stringify(exec(it, scope)) }
                is Seq -> node.exprs.lastOrNull()?.let { exec(it, scope) } ?: Undefined
            }
        }

        private fun typeName(value: Any?): String = when (value) {
            null -> "object"
            Undefined -> "undefined"
            is String -> "string"
            is Double, is Int -> "number"
            is Boolean -> "boolean"
            is JsFunction, is NativeFunction -> "function"
            else -> "object"
        }

        private fun evalTarget(node: Node, scope: Scope): Any? = when (node) {
            is Ident -> scope.get(node.name)
            is Member -> memberGet(exec(node.obj, scope), node, scope)
            else -> exec(node, scope)
        }

        private fun assignTo(node: Node, value: Any?, scope: Scope) {
            when (node) {
                is Ident -> scope.set(node.name, value)
                is Member -> {
                    val target = exec(node.obj, scope)
                    when (target) {
                        is MutableMap<*, *> -> {
                            @Suppress("UNCHECKED_CAST")
                            val map = target as MutableMap<String, Any?>
                            map[node.property] = value
                        }

                        is MutableList<*> -> {
                            val index = toNumber(if (node.computed != null) exec(node.computed, scope) else node.property).toInt()
                            @Suppress("UNCHECKED_CAST")
                            val list = target as MutableList<Any?>
                            list[index] = value
                        }

                        else -> throw JsError("不能给这个值赋属性")
                    }
                }

                else -> throw JsError("赋值目标不合法")
            }
        }

        private fun callFunction(callee: Any?, args: List<Any?>): Any? {
            tick()
            when (callee) {
                is NativeFunction -> return callee.body(args)
                is JsFunction -> {
                    val scope = Scope(callee.closure).apply { isFunction = true }
                    callee.params.forEachIndexed { index, param ->
                        if (param.startsWith("...")) {
                            scope.define(param.removePrefix("..."), args.drop(index).toMutableList())
                        } else {
                            scope.define(param, args.getOrNull(index) ?: Undefined)
                        }
                    }
                    scope.define("arguments", args.toMutableList())
                    return try {
                        val result = exec(callee.body, scope)
                        if (callee.exprBody) result else Undefined
                    } catch (signal: ReturnSignal) {
                        signal.value
                    }
                }

                else -> throw JsError("${stringify(callee)} 不是函数，不能调用")
            }
        }

        private fun memberGet(target: Any?, node: Member, scope: Scope): Any? {
            if (target == null || target == Undefined) {
                if (node.optional) return Undefined
                throw JsError("试图读取 ${stringify(target)} 的属性 '${node.property}'")
            }
            val key = if (node.computed != null) stringify(exec(node.computed, scope)) else node.property

            return when (target) {
                is String -> {
                    when (key) {
                        "length" -> target.length.toDouble()
                        "toUpperCase" -> return NativeFunction { target.uppercase() }
                        "toLowerCase" -> return NativeFunction { target.lowercase() }
                        "trim" -> return NativeFunction { target.trim() }
                        "includes" -> return NativeFunction { args -> target.contains(stringify(args.firstOrNull())) }
                        "indexOf" -> return NativeFunction { args -> target.indexOf(stringify(args.firstOrNull())).toDouble() }
                        "split" -> return NativeFunction { args ->
                            val separator = stringify(args.firstOrNull())
                            if (separator.isEmpty()) target.map { it.toString() }.toMutableList()
                            else target.split(separator).toMutableList()
                        }

                        "replace" -> return NativeFunction { args ->
                            target.replace(stringify(args.firstOrNull()), stringify(args.getOrNull(1)))
                        }

                        "slice" -> return NativeFunction { args ->
                            val start = toNumber(args.firstOrNull()).toInt()
                            val end = args.getOrNull(1)?.let { toNumber(it).toInt() } ?: target.length
                            target.substring(start.coerceIn(0, target.length), end.coerceIn(0, target.length))
                        }

                        "charAt" -> return NativeFunction { args -> target.getOrNull(toNumber(args.firstOrNull()).toInt())?.toString() ?: "" }
                        "repeat" -> return NativeFunction { args -> target.repeat(toNumber(args.firstOrNull()).toInt().coerceIn(0, 100)) }
                        "startsWith" -> return NativeFunction { args -> target.startsWith(stringify(args.firstOrNull())) }
                        "endsWith" -> return NativeFunction { args -> target.endsWith(stringify(args.firstOrNull())) }
                        "substring" -> return NativeFunction { args ->
                            val start = toNumber(args.firstOrNull()).toInt().coerceIn(0, target.length)
                            val end = args.getOrNull(1)?.let { toNumber(it).toInt().coerceIn(0, target.length) } ?: target.length
                            target.substring(minOf(start, end), maxOf(start, end))
                        }

                        else -> return target.toDoubleOrNull()?.let { it } ?: Undefined
                    }
                }

                is MutableList<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    val list = target as MutableList<Any?>
                    when (key) {
                        "length" -> return list.size.toDouble()
                        "push" -> return NativeFunction { args -> list.addAll(args); list.size.toDouble() }
                        "pop" -> return NativeFunction { if (list.isEmpty()) Undefined else list.removeAt(list.size - 1) }
                        "shift" -> return NativeFunction { if (list.isEmpty()) Undefined else list.removeAt(0) }
                        "unshift" -> return NativeFunction { args -> list.addAll(0, args); list.size.toDouble() }
                        "join" -> return NativeFunction { args -> list.joinToString(stringify(args.firstOrNull() ?: ",")) { stringify(it) } }
                        "indexOf" -> return NativeFunction { args -> list.indexOfFirst { looseEquals(it, args.firstOrNull()) }.toDouble() }
                        "includes" -> return NativeFunction { args -> list.any { looseEquals(it, args.firstOrNull()) } }
                        "slice" -> return NativeFunction { args ->
                            val start = toNumber(args.firstOrNull()).toInt().let { if (it < 0) list.size + it else it }
                            val end = args.getOrNull(1)?.let { toNumber(it).toInt().let { v -> if (v < 0) list.size + v else v } } ?: list.size
                            list.subList(start.coerceIn(0, list.size), end.coerceIn(0, list.size)).toMutableList()
                        }

                        "map" -> return NativeFunction { args ->
                            val fn = args.firstOrNull()
                            list.mapIndexed { index, item -> callFunction(fn, listOf(item, index.toDouble(), list)) }.toMutableList()
                        }

                        "filter" -> return NativeFunction { args ->
                            val fn = args.firstOrNull()
                            list.filterIndexed { index, item -> truthy(callFunction(fn, listOf(item, index.toDouble(), list))) }.toMutableList()
                        }

                        "find" -> return NativeFunction { args ->
                            val fn = args.firstOrNull()
                            list.firstOrNull { truthy(callFunction(fn, listOf(it))) } ?: Undefined
                        }

                        "forEach" -> return NativeFunction { args ->
                            val fn = args.firstOrNull()
                            list.forEachIndexed { index, item -> callFunction(fn, listOf(item, index.toDouble())) }
                            Undefined
                        }

                        "some" -> return NativeFunction { args ->
                            val fn = args.firstOrNull()
                            list.any { truthy(callFunction(fn, listOf(it))) }
                        }

                        "every" -> return NativeFunction { args ->
                            val fn = args.firstOrNull()
                            list.all { truthy(callFunction(fn, listOf(it))) }
                        }

                        "reduce" -> return NativeFunction { args ->
                            val fn = args.firstOrNull()
                            var acc: Any? = if (args.size > 1) args[1] else Undefined
                            var started = args.size > 1
                            list.forEach { item ->
                                if (!started) {
                                    acc = item
                                    started = true
                                } else {
                                    acc = callFunction(fn, listOf(acc, item))
                                }
                            }
                            acc
                        }

                        "sort" -> return NativeFunction {
                            list.sortWith { a, b -> stringify(a).compareTo(stringify(b)) }
                            list
                        }

                        "reverse" -> return NativeFunction {
                            list.reverse()
                            list
                        }

                        else -> {
                            val index = key.toIntOrNull() ?: return Undefined
                            return list.getOrNull(index) ?: Undefined
                        }
                    }
                }

                is Map<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    val map = target as Map<String, Any?>
                    when (key) {
                        "keys" -> NativeFunction { map.keys.toMutableList() }
                        "values" -> NativeFunction { map.values.toMutableList() }
                        "has" -> NativeFunction { args -> map.containsKey(stringify(args.firstOrNull())) }
                        "get" -> NativeFunction { args -> map[stringify(args.firstOrNull())] ?: Undefined }
                        else -> map[key] ?: throw JsError("对象上没有 '$key' 这个成员")
                    }
                }

                else -> throw JsError("不认识的值类型：${target::class.java.simpleName}")
            }
        }

        private fun binary(op: String, left: Any?, right: Any?): Any? = when (op) {
            "+" -> plus(left, right)
            "-" -> toNumber(left) - toNumber(right)
            "*" -> toNumber(left) * toNumber(right)
            "/" -> toNumber(left) / toNumber(right)
            "%" -> toNumber(left) % toNumber(right)
            "**" -> Math.pow(toNumber(left), toNumber(right))
            "==", "===" -> looseEquals(left, right)
            "!=", "!==" -> !looseEquals(left, right)
            "<" -> compare(left, right) < 0
            ">" -> compare(left, right) > 0
            "<=" -> compare(left, right) <= 0
            ">=" -> compare(left, right) >= 0
            else -> Undefined
        }

        private fun compare(left: Any?, right: Any?): Int = when {
            left is String && right is String -> left.compareTo(right)
            else -> toNumber(left).compareTo(toNumber(right))
        }

        private fun plus(left: Any?, right: Any?): Any? {
            if (left is String || right is String) return stringify(left) + stringify(right)
            if (left is MutableList<*> && right is List<*>) {
                val merged = ArrayList<Any?>(left)
                merged.addAll(right)
                return merged
            }
            return toNumber(left) + toNumber(right)
        }

        private fun looseEquals(left: Any?, right: Any?): Boolean {
            if (left == right) return true
            if ((left == null || left == Undefined) && (right == null || right == Undefined)) return true
            if (left is Double && right is Double) return left == right
            return stringify(left) == stringify(right)
        }

        fun truthy(value: Any?): Boolean = when (value) {
            null, Undefined -> false
            is Boolean -> value
            is Double -> value != 0.0 && !value.isNaN()
            is String -> value.isNotEmpty()
            is List<*> -> true
            else -> true
        }

        fun toNumber(value: Any?): Double = when (value) {
            null, Undefined -> Double.NaN
            is Double -> value
            is Boolean -> if (value) 1.0 else 0.0
            is String -> value.trim().toDoubleOrNull() ?: Double.NaN
            else -> Double.NaN
        }
    }

    private class NativeFunction(val body: (List<Any?>) -> Any?)
}

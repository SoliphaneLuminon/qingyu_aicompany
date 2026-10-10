package com.qingyu.app.modules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JS 子集解释器的回归测试喵。
 *
 * 这个解释器是"模块系统"的全部计算能力，语法支持一旦退化，用户装的插件就会静默失效喵，
 * 所以这里用真正的模块脚本形态来跑，而不是写玩具表达式喵。
 */
class SimpleJsEngineTest {

    private class RecordingBridge : ModuleHost {
        val pushed = mutableListOf<String>()
        val logs = mutableListOf<String>()
        private val values = HashMap<String, String>()

        override fun personaName(): String = "苏婉"

        override fun recentMessages(count: Int): String = (1..count).joinToString("\n") { "第 $it 条消息" }

        override fun pushMessage(text: String) {
            pushed.add(text)
        }

        override fun getValue(key: String): String = values[key].orEmpty()

        override fun setValue(key: String, value: String) {
            values[key] = value
        }

        override fun now(): Long = 1_700_000_000_000L
    }

    private fun run(
        code: String,
        bridge: RecordingBridge = RecordingBridge(),
        console: StringBuilder = StringBuilder(),
    ): SimpleJsEngine.Result {
        val jsBridge = JsBridge(host = bridge, console = console)
        return SimpleJsEngine().execute(code, jsBridge, maxSteps = 100_000)
    }

    @Test
    fun `变量与算术`() {
        val result = run("var a = 2; var b = 3; a * b + 1;")
        assertEquals(null, result.error)
        assertEquals("7", result.returnValue)
    }

    @Test
    fun `函数与闭包`() {
        val code = """
            function makeCounter() {
                var count = 0;
                return function () {
                    count = count + 1;
                    return count;
                };
            }
            var next = makeCounter();
            next();
            next();
            next();
        """.trimIndent()
        val result = run(code)
        assertEquals(null, result.error)
        assertEquals("3", result.returnValue)
    }

    @Test
    fun `数组方法与循环`() {
        val code = """
            var nums = [3, 1, 4, 1, 5];
            var doubled = nums.map(function (n) { return n * 2; });
            var total = doubled.reduce(function (a, b) { return a + b; }, 0);
            total;
        """.trimIndent()
        val result = run(code)
        assertEquals(null, result.error)
        assertEquals("28", result.returnValue)
    }

    @Test
    fun `字符串操作与模板字符串`() {
        val code = "var name = '苏婉'; var msg = `你好，` + name + `！`; msg.length;"
        val result = run(code)
        assertEquals(null, result.error)
        assertEquals("6", result.returnValue)
    }

    @Test
    fun `宿主接口可以写入消息`() {
        val bridge = RecordingBridge()
        val console = StringBuilder()
        val code = """
            var name = qingyu.personaName();
            var recent = qingyu.recentMessages(3);
            var lines = recent.split("\n");
            console.log("共 " + lines.length + " 条");
            qingyu.setValue("seen", lines.length);
            qingyu.pushMessage("给" + name + "的总结");
            qingyu.getValue("seen");
        """.trimIndent()
        val result = run(code, bridge, console)
        assertEquals(null, result.error)
        assertEquals(listOf("给苏婉的总结"), bridge.pushed)
        assertEquals("3", result.returnValue)
        assertTrue("console.log 应当收到内容", console.toString().contains("共 3 条"))
    }

    @Test
    fun `while 与 break`() {
        val code = """
            var i = 0;
            var sum = 0;
            while (true) {
                i = i + 1;
                if (i > 5) { break; }
                sum = sum + i;
            }
            sum;
        """.trimIndent()
        val result = run(code)
        assertEquals(null, result.error)
        assertEquals("15", result.returnValue)
    }

    @Test
    fun `try catch 能接住宿主抛出的错误`() {
        val code = """
            var message = "没出错";
            try {
                qingyu.不存在的接口();
            } catch (e) {
                message = "被拦住了：" + e;
            }
            message;
        """.trimIndent()
        val result = run(code)
        assertEquals("执行不该失败：${result.error}", null, result.error)
        assertTrue("应当走进 catch，实际：${result.returnValue}", result.returnValue?.startsWith("被拦住了") == true)
    }

    @Test
    fun `访问不存在的成员会明确报错`() {
        val result = run("qingyu.不存在的接口();")
        assertTrue("应当提示成员不存在，实际：${result.error}", result.error?.contains("没有") == true)
    }

    @Test
    fun `死循环会被步数上限终止`() {
        val result = run("while (true) { }")
        assertTrue("应当报错而不是卡死", result.error != null)
    }

    @Test
    fun `对象字面量与属性访问`() {
        val code = """
            var card = { name: "苏婉", age: 23 };
            card.age = card.age + 1;
            card.name + card.age;
        """.trimIndent()
        val result = run(code)
        assertEquals(null, result.error)
        assertEquals("苏婉24", result.returnValue)
    }

    @Test
    fun `const 不能被重新赋值`() {
        val result = run("const a = 1; a = 2; a;")
        assertTrue(result.error != null)
    }
}

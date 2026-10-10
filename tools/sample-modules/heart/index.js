// 心动值：统计最近的对话热度，并给出一句反馈。
// 演示模块能力：读上下文、做算术、把结果写回聊天。

var messages = qingyu.recentMessages(20);
var lines = messages.split("\n").filter(function (line) {
    return line.trim().length > 0;
});

var warm = 0;
var questionMarks = 0;
var longest = 0;

for (var i = 0; i < lines.length; i++) {
    var line = lines[i];
    if (line.indexOf("想你") >= 0 || line.indexOf("喜欢") >= 0 || line.indexOf("抱") >= 0) {
        warm = warm + 2;
    }
    if (line.indexOf("?") >= 0 || line.indexOf("？") >= 0) {
        questionMarks = questionMarks + 1;
    }
    if (line.length > longest) {
        longest = line.length;
    }
}

var score = warm * 10 + questionMarks * 3 + Math.floor(longest / 4);
if (score > 100) {
    score = 100;
}

console.log("分析了 " + lines.length + " 条消息");
console.log("热词命中 +" + warm * 10 + "，提问 +" + questionMarks * 3 + "，最长句 +" + Math.floor(longest / 4));

var comment = "平平淡淡，也挺好。";
if (score >= 80) {
    comment = "这段对话很热乎，继续保持。";
} else if (score >= 50) {
    comment = "还算亲近，可以再多说两句。";
}

qingyu.setValue("lastScore", score);
qingyu.pushMessage("【心动值 " + score + "】" + comment);

"心动值 " + score + " —— " + comment;

// 每日签：根据日期给一句属于今天的话，并写进聊天。
// 演示模块能力：读宿主时间、做字符串处理、写回消息。

var now = qingyu.now();
var dayIndex = Math.floor(now / 86400000);

var lines = [
    "今天适合把拖了很久的那件小事做掉。",
    "今天适合早点睡，明天的你会谢谢今天的你。",
    "今天适合给一个很久没联系的人发条消息。",
    "今天适合少想一点，先做五分钟再说。",
    "今天适合把手机放远一点，认真吃一顿饭。",
    "今天适合夸自己一句，不用等别人先开口。",
    "今天适合出门走走，哪怕只是楼下转一圈。"
];

var index = dayIndex % lines.length;
if (index < 0) {
    index = index + lines.length;
}
var pick = lines[index];

console.log("今天是第 " + (index + 1) + " 条签。");
qingyu.pushMessage("【每日签】" + pick);

pick;

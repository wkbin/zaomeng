---
address_suffixes:
  - 哥哥
  - 姐姐
  - 妹妹
  - 弟弟
  - 姑娘
  - 公子
  - 爷
speech_verbs:
  - 道
  - 说道
  - 笑道
  - 问道
  - 答道
  - 喝道
  - 叫道
  - 叹道
  - 呼道
object_leaders:
  - 叫
  - 唤
  - 问
  - 对
  - 向
  - 同
  - 与
  - 将
  - 把
  - 便
  - 扶住
  - 拉住
  - 搀起
  - 扶起
  - 扶着
  - 忙
  - 忙呼
  - 喝住
  - 捉住
  - 拿住
  - 推着
  - 拖着
  - 说
  - 敬
stop_names:
  - 我们
  - 你们
  - 他们
  - 她们
  - 自己
  - 那里
  - 这里
  - 这个
  - 那个
  - 一种
  - 一个
common_surnames: "赵钱孙李周吴郑王冯陈褚卫蒋沈韩杨朱秦尤许何吕施张孔曹严华金魏陶姜戚谢邹喻柏水窦章云苏潘葛范彭郎鲁韦昌马苗凤花方俞任袁柳酆鲍史唐费廉岑薛雷贺倪汤滕殷罗毕郝邬安常乐于时傅皮卞齐康伍余元卜顾孟平黄和穆萧尹姚邵湛汪祁毛禹狄米贝明臧计伏成戴谈宋茅庞熊纪舒屈项祝董梁杜阮蓝闵席季麻强贾路娄危江童颜郭梅盛林刁钟徐邱骆高夏蔡田樊胡凌霍虞万支柯昝管卢莫经房裘缪干解应宗丁宣贲邓郁单杭洪包诸左石崔吉钮龚程嵇邢滑裴陆荣翁荀羊惠甄曲家封芮羿储靳汲邴糜松井段富巫焦巴弓牧隗山谷车侯宓蓬全郗班仰秋仲伊宫宁仇栾暴甘钭厉戎祖武符刘景詹束龙叶幸司韶郜黎"
trait_keywords:
  勇敢: [勇, 冲, 无畏, 果断]
  温柔: [轻声, 温和, 安慰, 体贴]
  聪慧: [思索, 推断, 聪明, 机敏]
  敏感: [委屈, 难过, 心酸, 叹息]
  傲气: [冷笑, 不屑, 高傲, 轻蔑]
  忠诚: [守护, 忠, 誓言, 不离]
  善良: [帮助, 善意, 宽容, 劝解]
  执拗: [坚持, 非要, 绝不, 固执]
  机变: [变化, 试探, 识破, 周旋]
  诙谐: [笑道, 打趣, 顽皮, 戏弄]
  虔诚: [佛, 祈祷, 经文, 戒律]
  沉稳: [稳住, 接应, 收拢, 不慌]
  圆滑: [不如, 且慢, 何必, 先看看]
  克制: [忍住, 先忍, 不动声色, 收着]
value_markers:
  勇气:
    positive: [冲, 断, 扛, 来, 探路, 上前, 顶住, 硬扛]
    negative: [退后, 缩, 怕, 不敢]
  智慧:
    positive: [思量, 计较, 试探, 变化, 识破, 探明, 看清, 分辨]
    negative: [糊涂, 上当, 轻信]
  善良:
    positive: [慈悲, 恕, 救, 护, 帮, 安慰, 体谅]
    negative: [害命, 伤人, 苛待, 折磨]
  忠诚:
    positive: [跟随, 守住, 护着, 承诺, 同行, 接应, 师门, 同伴]
    negative: [散伙, 丢下, 背离]
  正义:
    positive: [善恶, 天理, 公道, 罪过, 正路, 规矩]
    negative: [枉杀, 作恶, 欺心]
  自由:
    positive: [自在, 逍遥, 快活, 不受拘束, 随心]
    negative: [拘束, 受制, 被逼]
  野心:
    positive: [称王, 做官, 名号, 本事, 抬高, 图谋]
    negative: [不图, 无意争锋]
  责任:
    positive: [后路, 行李, 安顿, 守住, 扛住, 照应]
    negative: [误事, 偷懒, 撂下]
archetypes:
  adaptive_initiator:
    markers: [先探, 真假, 破局, 当前, 出手, 顶上]
    traits: [勇敢, 聪慧, 机变, 傲气]
    value_bias: {勇气: 2, 智慧: 2, 自由: 1}
  grounded_pragmatist:
    markers: [吃亏, 便宜, 退路, 省力, 算计, 划算]
    traits: [诙谐, 圆滑, 善良]
    value_bias: {自由: 2, 智慧: 1, 忠诚: 1}
  moral_guardian:
    markers: [本心, 慈悲, 戒律, 因果, 规矩, 劝解]
    traits: [虔诚, 温柔, 善良, 克制]
    value_bias: {善良: 3, 责任: 2, 正义: 2, 忠诚: 1}
  steady_supporter:
    markers: [接应, 后路, 收拢, 稳住, 扛住, 照应]
    traits: [沉稳, 忠诚, 善良]
    value_bias: {责任: 3, 忠诚: 2, 善良: 1}
---

# DISTILLATION RULES

This file keeps extraction markers and lightweight structure only.
Natural-language profile prose should be produced by evidence plus LLM refinement.

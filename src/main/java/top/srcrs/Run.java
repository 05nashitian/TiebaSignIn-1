package top.srcrs;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.srcrs.domain.Cookie;
import top.srcrs.util.Encryption;
import top.srcrs.util.Request;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 程序运行开始的地方
 */
public class Run {
    private static final Logger LOGGER = LoggerFactory.getLogger(Run.class);

    String LIKE_URL  = "https://tieba.baidu.com/mo/q/newmoindex";
    String TBS_URL   = "http://tieba.baidu.com/dc/common/tbs";
    String SIGN_URL  = "http://c.tieba.baidu.com/c/c/forum/sign";

    private List<String> follow  = new ArrayList<>();
    private static List<String> success = new ArrayList<>();
    private static Set<String>  failed  = new HashSet<>();
    private static List<String> invalid = new ArrayList<>();
    private String tbs = "";
    private static Integer followNum = 0;

    public static void main(String[] args) {
        // 1. 从 args[0] 读取 BDUSS
        if (args.length == 0) {
            LOGGER.warn("请在运行参数中填写 BDUSS");
            return;
        }
        Cookie cookie = Cookie.getInstance();
        cookie.setBDUSS(args[0]);

        Run run = new Run();
        // 2. 获取 tbs
        run.getTbs();
        // 3. 获取关注贴吧列表
        run.getFollow();
        followNum = followNum == 0 ? follow.size() + invalid.size() : followNum;
        // 4. 执行签到
        run.runSign();

        // 5. 日志输出
        LOGGER.info("共 {} 个贴吧 - 成功: {} - 失败: {}", followNum, success.size(), followNum - success.size());
        LOGGER.info("失效 {} 个贴吧: {}", invalid.size(), invalid);

        // 6. 原有 PushPlus 通知（如果使用）
        if (args.length == 2) {
            run.send(args[1]);
        }

        // 7. 新增：Telegram Bot 通知
        String tgToken  = System.getenv("TELEGRAM_BOT_TOKEN");
        String tgChatId = System.getenv("TELEGRAM_CHAT_ID");
        if (tgToken != null && tgChatId != null) {
            // 构建摘要
            String text = "贴吧签到结果：\n" +
                          "总: " + followNum + "，成功: " + success.size() + "，失败: " + (followNum - success.size());
            String desp = "成功列表: " + success + "\n" +
                          "失败列表: " + failed + "\n" +
                          "失效列表: " + invalid;
            run.sendTelegram(tgToken, tgChatId, text + "\n\n" + desp);
        } else {
            LOGGER.warn("未配置 TELEGRAM_BOT_TOKEN/TELEGRAM_CHAT_ID，跳过 Telegram 通知");
        }
    }

    public void getTbs() {
        try {
            JSONObject jsonObject = Request.get(TBS_URL);
            if ("1".equals(jsonObject.getString("is_login"))) {
                LOGGER.info("获取 tbs 成功");
                tbs = jsonObject.getString("tbs");
            } else {
                LOGGER.warn("获取 tbs 失败 -- " + jsonObject);
            }
        } catch (Exception e) {
            LOGGER.error("获取 tbs 部分出现错误 -- ", e);
        }
    }

    public void getFollow() {
        try {
            JSONObject jsonObject = Request.get(LIKE_URL);
            LOGGER.info("获取贴吧列表成功");
            JSONArray list = jsonObject.getJSONObject("data").getJSONArray("like_forum");
            followNum = list.size();
            for (Object o : list) {
                JSONObject jo = (JSONObject) o;
                String forum = jo.getString("forum_name");
                if ("0".equals(jo.getString("is_sign"))) {
                    String enc = URLEncoder.encode(forum, "UTF-8");
                    if (Request.isTiebaNotExist(forum)) {
                        invalid.add(forum);
                        failed.add(forum);
                    } else {
                        follow.add(enc);
                    }
                } else {
                    success.add(forum);
                }
            }
        } catch (Exception e) {
            LOGGER.error("获取关注贴吧出现错误 -- ", e);
        }
    }

    public void runSign() {
        int rounds = 5;
        try {
            while (success.size() < followNum && rounds-- > 0) {
                LOGGER.info("----- 第 {} 轮签到开始 -----", 6 - rounds);
                for (Iterator<String> it = follow.iterator(); it.hasNext(); ) {
                    String enc = it.next();
                    String forum = URLDecoder.decode(enc, "UTF-8");
                    String body = "kw=" + enc +
                                  "&tbs=" + tbs +
                                  "&sign=" + Encryption.enCodeMd5("kw=" + forum + "tbs=" + tbs + "tiebaclient!!!");
                    JSONObject res = Request.post(SIGN_URL, body);
                    TimeUnit.MILLISECONDS.sleep(new Random().nextInt(200) + 300);
                    if ("0".equals(res.getString("error_code"))) {
                        it.remove();
                        success.add(forum);
                        failed.remove(forum);
                        LOGGER.info("{}: 签到成功", forum);
                    } else {
                        failed.add(forum);
                        LOGGER.warn("{}: 签到失败 -- {}", forum, res);
                    }
                }
                if (success.size() < followNum - invalid.size()) {
                    Thread.sleep(5 * 60 * 1000);
                    getTbs();
                }
            }
        } catch (Exception e) {
            LOGGER.error("签到过程中出现错误 -- ", e);
        }
    }

    /** 原有 PushPlus 发送方法略 */

    /**
     * 使用 Telegram Bot API 推送消息
     */
    public void sendTelegram(String botToken, String chatId, String message) {
        try {
            String encoded = URLEncoder.encode(message, "UTF-8");
            String url = String.format(
                "https://api.telegram.org/bot%s/sendMessage?chat_id=%s&text=%s&disable_web_page_preview=true",
                botToken, chatId, encoded
            );
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            try (BufferedReader in = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = in.readLine()) != null) {
                    // 可选：打印 Telegram 返回内容
                    System.out.println("Telegram 返回: " + line);
                }
            }
        } catch (Exception e) {
            LOGGER.error("Telegram 推送失败 -- ", e);
        }
    }
}

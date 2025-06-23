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
import java.net.URLDecoder;           // ← 新增
import java.util.*;
import java.util.concurrent.TimeUnit;

public class Run {
    private static final Logger LOGGER = LoggerFactory.getLogger(Run.class);

    // 接口地址
    private static final String LIKE_URL  = "https://tieba.baidu.com/mo/q/newmoindex";
    private static final String TBS_URL   = "http://tieba.baidu.com/dc/common/tbs";
    private static final String SIGN_URL  = "http://c.tieba.baidu.com/c/c/forum/sign";

    // 存关注贴吧、成功、失败、失效
    private List<String> follow  = new ArrayList<>();
    private static List<String> success = new ArrayList<>();
    private static Set<String>  failed  = new HashSet<>();
    private static List<String> invalid = new ArrayList<>();

    private String tbs = "";
    private static int followNum = 0;

    public static void main(String[] args) {
        if (args.length == 0) {
            LOGGER.warn("请在运行参数中填写 BDUSS");
            return;
        }
        // 初始化 Cookie
        Cookie cookie = Cookie.getInstance();
        cookie.setBDUSS(args[0]);

        Run run = new Run();

        // 1. 拿 tbs
        run.getTbs();

        // 2. 拿关注的贴吧列表
        run.getFollow();

        // 统计总数（加上失效的）
        followNum = success.size() + run.follow.size() + invalid.size();

        // 3. 签到
        run.runSign();

        // 4. 日志输出
        LOGGER.info("共 {} 个贴吧 → 成功: {}，失败: {}，失效: {}",
                followNum, success.size(), followNum - success.size() - invalid.size(), invalid.size());

        // 5. PushPlus 通知（如果传了第二个参数）
        if (args.length >= 2 && args[1] != null && !args[1].isEmpty()) {
            run.sendPushPlus(args[1]);
        }

        // 6. Telegram 通知
        String tgToken  = System.getenv("TELEGRAM_BOT_TOKEN");
        String tgChatId = System.getenv("TELEGRAM_CHAT_ID");
        if (tgToken != null && tgChatId != null) {
            String text = String.format(
                "贴吧签到结果：总 %d，成功 %d，失败 %d，失效 %d",
                followNum, success.size(),
                followNum - success.size() - invalid.size(),
                invalid.size()
            );
            String detail = String.format(
                "成功列表: %s\n失败列表: %s\n失效列表: %s",
                success, failed, invalid
            );
            run.sendTelegram(tgToken, tgChatId, text + "\n\n" + detail);
        } else {
            LOGGER.warn("未配置 TELEGRAM_BOT_TOKEN/TELEGRAM_CHAT_ID，跳过 Telegram 通知");
        }
    }

    /** 1. 获取 tbs */
    public void getTbs() {
        try {
            JSONObject json = Request.get(TBS_URL);
            if ("1".equals(json.getString("is_login"))) {
                tbs = json.getString("tbs");
                LOGGER.info("获取 tbs 成功: {}", tbs);
            } else {
                LOGGER.warn("获取 tbs 失败: {}", json);
            }
        } catch (Exception e) {
            LOGGER.error("获取 tbs 出错", e);
        }
    }

    /** 2. 获取关注的贴吧列表 */
    public void getFollow() {
        try {
            JSONObject json = Request.get(LIKE_URL);
            LOGGER.info("获取关注列表成功");
            JSONArray arr = json.getJSONObject("data").getJSONArray("like_forum");
            for (Object o : arr) {
                JSONObject jo = (JSONObject) o;
                String name = jo.getString("forum_name");
                boolean signed = "1".equals(jo.getString("is_sign"));
                if (signed) {
                    success.add(name);
                } else if (Request.isTiebaNotExist(name)) {
                    invalid.add(name);
                    failed.add(name);
                } else {
                    // 对 + 做 URL 编码
                    follow.add(URLEncoder.encode(name, "UTF-8"));
                }
            }
        } catch (Exception e) {
            LOGGER.error("获取关注列表出错", e);
        }
    }

    /** 3. 循环执行签到 */
    public void runSign() {
        int rounds = 5;
        try {
            while (!follow.isEmpty() && rounds-- > 0) {
                LOGGER.info("----- 第 {} 轮签到开始, 待签到 {} 个 -----", 6 - rounds, follow.size());
                Iterator<String> it = follow.iterator();
                while (it.hasNext()) {
                    String enc = it.next();
                    // 解码回原名
                    String forum = URLDecoder.decode(enc, "UTF-8");
                    // 构造签名 body
                    String signStr = "kw=" + forum + "tbs=" + tbs + "tiebaclient!!!";
                    String md5   = Encryption.enCodeMd5(signStr);
                    String body  = "kw=" + enc + "&tbs=" + tbs + "&sign=" + md5;

                    JSONObject res = Request.post(SIGN_URL, body);
                    TimeUnit.MILLISECONDS.sleep(new Random().nextInt(200) + 300);
                    if ("0".equals(res.getString("error_code"))) {
                        it.remove();
                        success.add(forum);
                        failed.remove(forum);
                        LOGGER.info("{} 签到成功", forum);
                    } else {
                        failed.add(forum);
                        LOGGER.warn("{} 签到失败: {}", forum, res);
                    }
                }
                if (!follow.isEmpty()) {
                    // 重新拿一次 tbs 再试
                    Thread.sleep(5 * 60 * 1000);
                    getTbs();
                }
            }
        } catch (Exception e) {
            LOGGER.error("签到过程出错", e);
        }
    }

    /** PushPlus 通知 */
    public void sendPushPlus(String token) {
        try {
            String text = "总:" + followNum + " 成功:" + success.size() + " 失败:" + (followNum - success.size());
            String desp = "成功:" + success + "\n失败:" + failed + "\n失效:" + invalid;
            String title   = URLEncoder.encode("百度贴吧签到", "UTF-8");
            String content = URLEncoder.encode(desp, "UTF-8");
            String urlStr  = String.format(
                "https://www.pushplus.plus/send?token=%s&title=%s&content=%s",
                token, title, content
            );
            HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setRequestMethod("GET");
            try (BufferedReader in = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                String line; while ((line = in.readLine()) != null) { /* ignore */ }
            }
            LOGGER.info("PushPlus 推送完成");
        } catch (Exception e) {
            LOGGER.error("PushPlus 推送失败", e);
        }
    }

    /** Telegram Bot 推送 */
    public void sendTelegram(String botToken, String chatId, String message) {
        try {
            String msgEnc = URLEncoder.encode(message, "UTF-8");
            String urlStr = String.format(
                "https://api.telegram.org/bot%s/sendMessage?chat_id=%s&text=%s&disable_web_page_preview=true",
                botToken, chatId, msgEnc
            );
            HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setRequestMethod("GET");
            try (BufferedReader in = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                String line; while ((line = in.readLine()) != null) { /* ignore */ }
            }
            LOGGER.info("Telegram 推送完成");
        } catch (Exception e) {
            LOGGER.error("Telegram 推送失败", e);
        }
    }
}

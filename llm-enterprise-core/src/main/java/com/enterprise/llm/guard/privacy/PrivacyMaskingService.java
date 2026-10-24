package com.enterprise.llm.guard.privacy;

import org.springframework.stereotype.Service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 入站敏感数据伪匿名化服务
 * 识别用户 Prompt 中的手机号、邮箱、身份证等实体，替换为 <PHONE_1> 等占位符并安全写入 PrivacyVault。
 */
@Service
public class PrivacyMaskingService {

    // 中国大陆手机号正则
    private static final Pattern PHONE_PATTERN = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    // 常见邮箱正则
    private static final Pattern EMAIL_PATTERN = Pattern.compile("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}");
    // 18位二代身份证正则 (含末位校验码 X/x)
    private static final Pattern ID_CARD_PATTERN = Pattern.compile("(?<!\\d)\\d{17}[\\dXx](?!\\d)");

    public String maskAndStore(String text, PrivacyVault vault) {
        if (text == null || text.isBlank() || vault == null) {
            return text;
        }

        String result = text;
        result = replaceWithVault(result, PHONE_PATTERN, "PHONE", vault);
        result = replaceWithVault(result, EMAIL_PATTERN, "EMAIL", vault);
        result = replaceWithVault(result, ID_CARD_PATTERN, "ID_CARD", vault);

        return result;
    }

    private String replaceWithVault(String input, Pattern pattern, String entityType, PrivacyVault vault) {
        Matcher matcher = pattern.matcher(input);
        if (!matcher.find()) {
            return input;
        }

        matcher.reset();
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String original = matcher.group();
            String placeholder = vault.getOrCreatePlaceholder(original, entityType);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(placeholder));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}

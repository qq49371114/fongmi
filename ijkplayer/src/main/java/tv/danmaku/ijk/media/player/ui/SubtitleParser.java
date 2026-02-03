package tv.danmaku.ijk.media.player.ui;

import android.text.Html;
import android.text.TextUtils;

// ✨✨✨ 核心修复：把新版的 Cue，换成老版的！ ✨✨✨
import com.google.android.exoplayer2.text.Cue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

public class SubtitleParser {

    private static final Pattern BRACES_PATTERN = Pattern.compile("\\{([^}]*)\\}");
    private static final String DIALOGUE_LINE_PREFIX = "Dialogue:";

    public static List<Cue> parse(String text) {
        if (TextUtils.isEmpty(text) || text.length() >= 512) {
            // ✨ 老版本 API 不支持返回 null，我们返回一个空列表
            return new ArrayList<>();
        }
        if (text.startsWith(DIALOGUE_LINE_PREFIX)) {
            text = parseDialogueLine(text);
        }
        text = text.replaceAll("\r\n", "<br>").replaceAll("\r", "<br>").replaceAll("\n", "<br>").replaceAll("\\{\\\\.*?\\}", "");
        if (text.endsWith("<br>")) {
            text = text.substring(0, text.lastIndexOf("<br>"));
        }
        
        // ✨✨✨ 核心修复：老版 Cue 的创建方式不一样！ ✨✨✨
        // 老版本没有 Cue.Builder()，我们直接 new 一个 Cue 对象
        return Arrays.asList(new Cue(Html.fromHtml(text)));
    }

    private static String parseDialogueLine(String text) {
        String[] lineValues = text.substring(DIALOGUE_LINE_PREFIX.length()).split(",");
        String rawText = lineValues[lineValues.length - 1];
        rawText = BRACES_PATTERN.matcher(rawText).replaceAll("");
        rawText = rawText.replace("\\N", "\n").replace("\\n", "\n").replace("\\h", "\u00A0");
        return rawText;
    }
}

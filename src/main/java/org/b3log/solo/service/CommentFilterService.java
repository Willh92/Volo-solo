/* SPDX-License-Identifier: AGPL-3.0-or-later */
package org.b3log.solo.service;

import com.github.houbb.sensitive.word.bs.SensitiveWordBs;
import com.github.houbb.sensitive.word.support.allow.WordAllows;
import com.github.houbb.sensitive.word.support.deny.WordDenys;
import com.github.houbb.sensitive.word.support.resultcondition.WordResultConditions;
import java.util.Arrays;
import java.util.List;
import org.b3log.latke.ioc.Inject;
import org.b3log.latke.ioc.Singleton;
import org.b3log.solo.model.Option;
import org.b3log.solo.util.Markdowns;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.parser.Parser;

/** An immutable DFA snapshot shared by new comments and visitor edits. No remote API is used. */
@Singleton
public class CommentFilterService {
    public static final String REJECTION = "评论包含不适合发表的词语，请修改后再试。";
    @Inject private OptionQueryService optionQueryService;
    private volatile Prepared current;

    private record Rules(boolean builtin, List<String> deny, List<String> allow) {
        static Rules read(final JSONObject preference) {
            if (preference == null) throw new IllegalStateException("Comment filter settings are unavailable");
            final String builtin = preference.optString(Option.ID_C_COMMENT_FILTER_BUILTIN, "on");
            if (!builtin.isBlank() && !builtin.equals("on") && !builtin.equals("off")) {
                throw new IllegalArgumentException("第三方评论词库开关不正确。");
            }
            return new Rules(!builtin.equals("off"), words(preference.optString(Option.ID_C_SPAM)),
                    words(preference.optString(Option.ID_C_COMMENT_FILTER_ALLOW)));
        }
    }

    public static final class Prepared {
        private final Rules rules;
        private final SensitiveWordBs matcher;
        private Prepared(final Rules rules, final SensitiveWordBs matcher) {
            this.rules = rules;
            this.matcher = matcher;
        }
        public boolean blocks(final String content) {
            if (matcher == null || content == null || content.isBlank()) return false;
            // Check both source and visible Markdown text; markup/entities must not split a forbidden word.
            return matcher.contains(text(content))
                    || matcher.contains(text(Jsoup.parseBodyFragment(Markdowns.toHTMLLocally(content)).text()));
        }
        private static String text(final String value) {
            return Parser.unescapeEntities(value, false).replaceAll("\\p{Cf}", "");
        }
    }

    public Prepared prepare(final JSONObject preference) {
        final Rules rules = Rules.read(preference);
        final Prepared previous = current;
        if (previous != null && previous.rules.equals(rules)) return previous;
        if (!rules.builtin && rules.deny.isEmpty()) return new Prepared(rules, null);
        final SensitiveWordBs matcher = SensitiveWordBs.newInstance()
                .wordDeny(WordDenys.chains(rules.builtin ? WordDenys.defaults() : WordDenys.empty(), rules::deny))
                .wordAllow(WordAllows.chains(rules.builtin ? WordAllows.defaults() : WordAllows.empty(), rules::allow))
                .ignoreCase(true).ignoreWidth(true).ignoreChineseStyle(true).ignoreEnglishStyle(true)
                .ignoreNumStyle(true).ignoreRepeat(false)
                .enableWordCheck(true).enableNumCheck(false).enableEmailCheck(false)
                .enableUrlCheck(false).enableIpv4Check(false)
                .wordResultCondition(WordResultConditions.englishWordMatch()).init();
        return new Prepared(rules, matcher);
    }

    /** Publish only after the preference transaction commits; readers retain their complete old snapshot. */
    public synchronized void activate(final Prepared prepared) { current = java.util.Objects.requireNonNull(prepared); }

    public void reload() { activate(prepare(optionQueryService.getPreference())); }

    public boolean blocked(final String content) {
        Prepared snapshot = current;
        if (snapshot == null) {
            synchronized (this) {
                snapshot = current;
                if (snapshot == null) {
                    snapshot = prepare(optionQueryService.getPreference());
                    current = snapshot;
                }
            }
        }
        return snapshot.blocks(content);
    }

    public static void validate(final JSONObject preference) { Rules.read(preference); }

    private static List<String> words(final String source) {
        if (source.length() > 20_000) throw new IllegalArgumentException("评论过滤词与白名单分别最多 20000 个字符。");
        final List<String> words = Arrays.stream(source.split("[,，\\r\\n]+"))
                .map(String::strip).filter(word -> !word.isEmpty()).distinct().toList();
        if (words.size() > 2000 || words.stream().anyMatch(word -> word.length() > 200)) {
            throw new IllegalArgumentException("每份评论词表最多 2000 个词，每个词最多 200 个字符。");
        }
        return words;
    }
}

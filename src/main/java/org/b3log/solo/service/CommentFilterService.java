/* SPDX-License-Identifier: AGPL-3.0-or-later */
package org.b3log.solo.service;

import com.github.houbb.sensitive.word.bs.SensitiveWordBs;
import com.github.houbb.sensitive.word.support.allow.WordAllows;
import com.github.houbb.sensitive.word.support.deny.WordDenys;
import com.github.houbb.sensitive.word.support.resultcondition.WordResultConditions;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.b3log.latke.ioc.Inject;
import org.b3log.latke.ioc.Singleton;
import org.b3log.solo.model.Option;
import org.b3log.solo.util.Markdowns;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.parser.Parser;

/** An immutable DFA snapshot shared by new comments and visitor edits, lazily initialized on first check. No remote API is used. */
@Singleton
public class CommentFilterService {
    public static final String REJECTION = "评论包含不适合发表的词语，请修改后再试。";
    @Inject private OptionQueryService optionQueryService;
    private volatile boolean configuredBuiltin = true;
    private volatile Set<String> customWords = Set.of();
    private volatile Set<String> allowWords = Set.of();
    private volatile String lastBuiltSignature;
    private volatile SensitiveWordBs wordBs;
    private volatile Prepared current;

    private record Rules(boolean builtin, List<String> deny, List<String> allow) {
        static Rules read(final JSONObject preference) {
            if (preference == null) throw new IllegalStateException("Comment filter settings are unavailable");
            final String builtin = preference.optString(Option.ID_C_COMMENT_FILTER_BUILTIN, "true").strip();
            if (!builtin.isEmpty() && !builtin.equals("true") && !builtin.equals("false")) {
                throw new IllegalArgumentException("第三方评论词库开关不正确。");
            }
            return new Rules(!"false".equals(builtin), words(preference.optString(Option.ID_C_SPAM)),
                    words(preference.optString(Option.ID_C_COMMENT_FILTER_ALLOW)));
        }

        String signature() {
            return builtin + "\0" + String.join("\n", deny) + "\0" + String.join("\n", allow);
        }
    }

    public static final class Prepared {
        private final CommentFilterService owner;
        private final Rules rules;
        private volatile SensitiveWordBs matcher;

        private Prepared(final CommentFilterService owner, final Rules rules) {
            this.owner = owner;
            this.rules = rules;
        }

        public boolean blocks(final String content) {
            if ((!rules.builtin && rules.deny.isEmpty()) || content == null || content.isBlank()) return false;
            SensitiveWordBs m = matcher;
            if (m == null) {
                m = (owner != null && (owner.current == this || owner.current == null))
                        ? owner.ensureWordBs(this)
                        : ensureDetachedMatcher();
            }
            if (m == null) return false;
            // Check both source and visible Markdown text; markup/entities must not split a forbidden word.
            return m.contains(text(content))
                    || m.contains(text(Jsoup.parseBodyFragment(Markdowns.toHTMLLocally(content)).text()));
        }

        private synchronized SensitiveWordBs ensureDetachedMatcher() {
            if (matcher == null && (rules.builtin || !rules.deny.isEmpty())) {
                matcher = buildMatcher(rules);
            }
            return matcher;
        }

        private static String text(final String value) {
            return Parser.unescapeEntities(value, false).replaceAll("\\p{Cf}", "");
        }
    }

    private static SensitiveWordBs buildMatcher(final Rules rules) {
        return SensitiveWordBs.newInstance()
                .wordDeny(WordDenys.chains(rules.builtin ? WordDenys.defaults() : WordDenys.empty(), rules::deny))
                .wordAllow(WordAllows.chains(rules.builtin ? WordAllows.defaults() : WordAllows.empty(), rules::allow))
                .ignoreCase(true).ignoreWidth(true).ignoreChineseStyle(true).ignoreEnglishStyle(true)
                .ignoreNumStyle(true).ignoreRepeat(false)
                .enableWordCheck(true).enableNumCheck(false).enableEmailCheck(false)
                .enableUrlCheck(false).enableIpv4Check(false)
                .wordResultCondition(WordResultConditions.englishWordMatch()).init();
    }

    private synchronized SensitiveWordBs ensureWordBs(final Prepared target) {
        if (target.matcher != null) {
            return target.matcher;
        }
        final Rules rules = target.rules;
        if (!rules.builtin && rules.deny.isEmpty()) {
            return null;
        }
        final String sig = rules.signature();
        if (sig.equals(this.lastBuiltSignature) && this.wordBs != null) {
            target.matcher = this.wordBs;
            return this.wordBs;
        }
        // Release any previous 35-60 MB builtin trie reference before constructing a new instance.
        this.wordBs = null;
        this.lastBuiltSignature = null;
        final SensitiveWordBs built = buildMatcher(rules);
        target.matcher = built;
        if (this.current == target || this.current == null) {
            this.configuredBuiltin = rules.builtin;
            this.customWords = Collections.unmodifiableSet(new LinkedHashSet<>(rules.deny));
            this.allowWords = Collections.unmodifiableSet(new LinkedHashSet<>(rules.allow));
            this.wordBs = built;
            this.lastBuiltSignature = sig;
        }
        return built;
    }

    public Prepared prepare(final JSONObject preference) {
        final Rules rules = Rules.read(preference);
        final Prepared previous = current;
        if (previous != null && previous.rules.equals(rules)) return previous;
        return new Prepared(this, rules);
    }

    /** Publish only after the preference transaction commits; readers retain their complete old snapshot. */
    public synchronized void activate(final Prepared prepared) {
        Objects.requireNonNull(prepared);
        final Rules rules = prepared.rules;
        this.configuredBuiltin = rules.builtin;
        this.customWords = Collections.unmodifiableSet(new LinkedHashSet<>(rules.deny));
        this.allowWords = Collections.unmodifiableSet(new LinkedHashSet<>(rules.allow));
        final String sig = rules.signature();
        if (!rules.builtin && rules.deny.isEmpty()) {
            this.wordBs = null;
            this.lastBuiltSignature = null;
        } else if (!sig.equals(this.lastBuiltSignature)) {
            this.wordBs = null;
            this.lastBuiltSignature = null;
        } else if (this.wordBs != null) {
            prepared.matcher = this.wordBs;
        }
        current = prepared;
    }

    public void reload() { activate(prepare(optionQueryService.getPreference())); }

    public boolean blocked(final String content) {
        Prepared snapshot = current;
        if (snapshot == null) {
            synchronized (this) {
                snapshot = current;
                if (snapshot == null) {
                    snapshot = prepare(optionQueryService.getPreference());
                    activate(snapshot);
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

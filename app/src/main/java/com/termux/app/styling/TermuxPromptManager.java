package com.termux.app.styling;

import android.content.Context;
import android.content.SharedPreferences;

import com.termux.app.TermuxActivity;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.terminal.TerminalSession;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class TermuxPromptManager {

    private static final String PREF_NAME = "termux_prompt_pref";
    private static final String KEY_CURRENT_PROMPT_ID = "current_prompt_id";
    public static final String DEFAULT_PROMPT_ID = "powerline_modern_double";

    public static class PromptItem {
        public final String id;
        public final String name;
        public final String category;
        public final String previewText;
        public final String ps1Script;

        public PromptItem(String id, String name, String category, String previewText, String ps1Script) {
            this.id = id;
            this.name = name;
            this.category = category;
            this.previewText = previewText;
            this.ps1Script = ps1Script;
        }
    }

    private static final List<PromptItem> S_PROMPTS = new ArrayList<>();

    static {
        // -------------------------------------------------------------
        // 🍬 分类一：圆角胶囊气泡流 (Bubbles / Pills) - 16 款
        // -------------------------------------------------------------
        add("bubble_tokyo_night", "东京夜色 (圆角气泡)", "圆角胶囊",
            " ⚡ termux   📁 ~/code   🌿 main \n╰─➤ ",
            "PS1='\\[\\e[0;34m\\]\\[\\e[44;37m\\] ⚡ termux \\[\\e[0;34;45m\\]\\[\\e[45;37m\\] 📁 \\W \\[\\e[0;35;42m\\]\\[\\e[42;30m\\] 🌿 main \\[\\e[0;32m\\]\\[\\e[0m\\]\\n\\[\\e[1;36m\\]╰─➤\\[\\e[0m\\] '");

        add("bubble_catppuccin", "马卡龙粉 (Catppuccin)", "圆角胶囊",
            " 🌸 termux   📁 ~/dev  ❯ ",
            "PS1='\\[\\e[0;35m\\]\\[\\e[45;37m\\] 🌸 termux \\[\\e[0;35;44m\\]\\[\\e[44;37m\\] 📁 \\W \\[\\e[0;34m\\]\\[\\e[0m\\] \\[\\e[1;35m\\]❯\\[\\e[0m\\] '");

        add("bubble_nord_cyan", "北极极光 (Nord冰蓝)", "圆角胶囊",
            " ❄️ nord   📁 ~/src   ⚡ ok \n╰─► ",
            "PS1='\\[\\e[0;36m\\]\\[\\e[46;30m\\] ❄️ nord \\[\\e[0;36;44m\\]\\[\\e[44;37m\\] 📁 \\W \\[\\e[0;34;42m\\]\\[\\e[42;30m\\] ⚡ ok \\[\\e[0;32m\\]\\[\\e[0m\\]\\n\\[\\e[1;36m\\]╰─►\\[\\e[0m\\] '");

        add("bubble_dracula_purple", "德古拉魅紫 (Dracula)", "圆角胶囊",
            " 🧛 dracula   📁 ~/lab  ❯ ",
            "PS1='\\[\\e[0;35m\\]\\[\\e[45;37m\\] 🧛 dracula \\[\\e[0;35;41m\\]\\[\\e[41;37m\\] 📁 \\W \\[\\e[0;31m\\]\\[\\e[0m\\] \\[\\e[1;35m\\]❯\\[\\e[0m\\] '");

        add("bubble_gruvbox_warm", "复古暖橙 (Gruvbox)", "圆角胶囊",
            " 🍂 gruvbox   📁 ~/code  » ",
            "PS1='\\[\\e[0;33m\\]\\[\\e[43;30m\\] 🍂 gruvbox \\[\\e[0;33;42m\\]\\[\\e[42;30m\\] 📁 \\W \\[\\e[0;32m\\]\\[\\e[0m\\] \\[\\e[1;33m\\]»\\[\\e[0m\\] '");

        add("bubble_cyber_neon", "霓虹朋克 (Cyberpunk)", "圆角胶囊",
            " ⚡ cyber   📁 ~/kernel  ⯈ ",
            "PS1='\\[\\e[0;32m\\]\\[\\e[42;30m\\] ⚡ cyber \\[\\e[0;32;45m\\]\\[\\e[45;37m\\] 📁 \\W \\[\\e[0;35m\\]\\[\\e[0m\\] \\[\\e[1;32m\\]⯈\\[\\e[0m\\] '");

        add("bubble_sunset_orange", "落日余晖 (Sunset)", "圆角胶囊",
            " 🌅 sunset   📁 ~/ai \n╰─➤ ",
            "PS1='\\[\\e[0;31m\\]\\[\\e[41;37m\\] 🌅 sunset \\[\\e[0;31;43m\\]\\[\\e[43;30m\\] 📁 \\W \\[\\e[0;33m\\]\\[\\e[0m\\]\\n\\[\\e[1;31m\\]╰─➤\\[\\e[0m\\] '");

        add("bubble_matrix_emerald", "黑客母体 (Emerald Matrix)", "圆角胶囊",
            " ⁕ matrix   📁 ~/core  ❯ ",
            "PS1='\\[\\e[0;32m\\]\\[\\e[42;30m\\] ⁕ matrix \\[\\e[0;32;40m\\]\\[\\e[40;32m\\] 📁 \\W \\[\\e[0;30m\\]\\[\\e[0m\\] \\[\\e[1;32m\\]❯\\[\\e[0m\\] '");

        add("bubble_deep_ocean", "深海蔚蓝 (Ocean Blue)", "圆角胶囊",
            " 🌊 ocean   📁 ~/deep  ≋ ",
            "PS1='\\[\\e[0;34m\\]\\[\\e[44;37m\\] 🌊 ocean \\[\\e[0;34;46m\\]\\[\\e[46;30m\\] 📁 \\W \\[\\e[0;36m\\]\\[\\e[0m\\] \\[\\e[1;34m\\]≋\\[\\e[0m\\] '");

        add("bubble_mint_fresh", "薄荷清爽 (Fresh Mint)", "圆角胶囊",
            " 🍃 mint   📁 ~/app  ❯ ",
            "PS1='\\[\\e[0;32m\\]\\[\\e[42;30m\\] 🍃 mint \\[\\e[0;32;44m\\]\\[\\e[44;37m\\] 📁 \\W \\[\\e[0;34m\\]\\[\\e[0m\\] \\[\\e[1;32m\\]❯\\[\\e[0m\\] '");

        add("bubble_crimson_flame", "赤焰烈火 (Crimson)", "圆角胶囊",
            " 🔥 flame   📁 ~/root \n╰─# ",
            "PS1='\\[\\e[0;31m\\]\\[\\e[41;37m\\] 🔥 flame \\[\\e[0;31;40m\\]\\[\\e[40;31m\\] 📁 \\W \\[\\e[0;30m\\]\\[\\e[0m\\]\\n\\[\\e[1;31m\\]╰─#\\[\\e[0m\\] '");

        add("bubble_galaxy_purple", "银河星系 (Galaxy)", "圆角胶囊",
            " 🌌 galaxy   📁 ~/cosmos  🪐 ",
            "PS1='\\[\\e[0;35m\\]\\[\\e[45;37m\\] 🌌 galaxy \\[\\e[0;35;46m\\]\\[\\e[46;30m\\] 📁 \\W \\[\\e[0;36m\\]\\[\\e[0m\\] \\[\\e[1;35m\\]🪐\\[\\e[0m\\] '");

        add("bubble_solarized_dark", "日光经典 (Solarized Dark)", "圆角胶囊",
            " ☀️ solar   📁 ~/work  ⇥ ",
            "PS1='\\[\\e[0;33m\\]\\[\\e[43;30m\\] ☀️ solar \\[\\e[0;33;46m\\]\\[\\e[46;30m\\] 📁 \\W \\[\\e[0;36m\\]\\[\\e[0m\\] \\[\\e[1;33m\\]⇥\\[\\e[0m\\] '");

        add("bubble_coffee_mocha", "摩卡咖啡 (Mocha)", "圆角胶囊",
            " ☕ mocha   📁 ~/daily  ❯ ",
            "PS1='\\[\\e[0;33m\\]\\[\\e[43;30m\\] ☕ mocha \\[\\e[0;33;45m\\]\\[\\e[45;37m\\] 📁 \\W \\[\\e[0;35m\\]\\[\\e[0m\\] \\[\\e[1;33m\\]❯\\[\\e[0m\\] '");

        add("bubble_candy_rainbow", "糖果彩虹 (Candy)", "圆角胶囊",
            " 🍬 candy   📁 ~/sweet   ⚡  ❯ ",
            "PS1='\\[\\e[0;35m\\]\\[\\e[45;37m\\] 🍬 candy \\[\\e[0;35;43m\\]\\[\\e[43;30m\\] 📁 \\W \\[\\e[0;33;42m\\]\\[\\e[42;30m\\] ⚡ \\[\\e[0;32m\\]\\[\\e[0m\\] \\[\\e[1;35m\\]❯\\[\\e[0m\\] '");

        add("bubble_mono_minimal", "极简黑白 (Mono Bubble)", "圆角胶囊",
            " termux   ~/project  $ ",
            "PS1='\\[\\e[0;37m\\]\\[\\e[47;30m\\] termux \\[\\e[0;37;40m\\]\\[\\e[40;37m\\] \\W \\[\\e[0;30m\\]\\[\\e[0m\\] $ '");

        // -------------------------------------------------------------
        // 🔷 分类二：Powerline 经典尖角色块流 (Segments) - 16 款
        // -------------------------------------------------------------
        add("powerline_agnoster", "Agnoster (鼻祖色块)", "Powerline尖角",
            " ⚡ termux  📁 ~/code  🌿 main  ",
            "PS1='\\[\\e[44;37m\\] ⚡ termux \\[\\e[0;34;42m\\]\\[\\e[42;30m\\] 📁 \\W \\[\\e[0;32;43m\\]\\[\\e[43;30m\\] 🌿 main \\[\\e[0;33m\\]\\[\\e[0m\\] '");

        add("powerline_paradox", "Paradox (双行尖角)", "Powerline尖角",
            " ⚡ [termux]  📁 ~/dev \n ╰─➤ ",
            "PS1='\\[\\e[45;37m\\] ⚡ [\\u] \\[\\e[0;35;44m\\]\\[\\e[44;37m\\] 📁 \\W \\[\\e[0;34m\\]\\[\\e[0m\\]\\n\\[\\e[1;35m\\] ╰─➤\\[\\e[0m\\] '");

        add("powerline_kali_blue", "Kali 渗透色块", "Powerline尖角",
            " 🐉 kali  📁 ~/recon  # ",
            "PS1='\\[\\e[44;37m\\] 🐉 kali \\[\\e[0;34;41m\\]\\[\\e[41;37m\\] 📁 \\W \\[\\e[0;31m\\]\\[\\e[0m\\] # '");

        add("powerline_arch_cyan", "Arch 青蓝尖角", "Powerline尖角",
            " 🏹 arch  📁 ~/pkg  ❯ ",
            "PS1='\\[\\e[46;30m\\] 🏹 arch \\[\\e[0;36;44m\\]\\[\\e[44;37m\\] 📁 \\W \\[\\e[0;34m\\]\\[\\e[0m\\] ❯ '");

        add("powerline_ubuntu_warm", "Ubuntu 橙褐尖角", "Powerline尖角",
            " 🐧 ubuntu  📁 ~/code  $ ",
            "PS1='\\[\\e[41;37m\\] 🐧 ubuntu \\[\\e[0;31;43m\\]\\[\\e[43;30m\\] 📁 \\W \\[\\e[0;33m\\]\\[\\e[0m\\] $ '");

        add("powerline_emerald_forest", "翡翠森林 (Emerald)", "Powerline尖角",
            " 🌲 forest  📁 ~/git  🌿 ok  ",
            "PS1='\\[\\e[42;30m\\] 🌲 forest \\[\\e[0;32;46m\\]\\[\\e[46;30m\\] 📁 \\W \\[\\e[0;36;42m\\]\\[\\e[42;30m\\] 🌿 ok \\[\\e[0;32m\\]\\[\\e[0m\\] '");

        add("powerline_crimson_dark", "深红暴风 (Crimson Storm)", "Powerline尖角",
            " 🔥 root  📁 ~/kernel  ⚡  ",
            "PS1='\\[\\e[41;37m\\] 🔥 root \\[\\e[0;31;40m\\]\\[\\e[40;31m\\] 📁 \\W \\[\\e[0;30;41m\\]\\[\\e[41;37m\\] ⚡ \\[\\e[0;31m\\]\\[\\e[0m\\] '");

        add("powerline_purple_haze", "紫雾迷幻 (Purple Haze)", "Powerline尖角",
            " 🔮 haze  📁 ~/ai  ⟡  ",
            "PS1='\\[\\e[45;37m\\] 🔮 haze \\[\\e[0;35;44m\\]\\[\\e[44;37m\\] 📁 \\W \\[\\e[0;34;45m\\]\\[\\e[45;37m\\] ⟡ \\[\\e[0;35m\\]\\[\\e[0m\\] '");

        add("powerline_gold_black", "黑金尊享 (Gold & Black)", "Powerline尖角",
            " 👑 pro  📁 ~/finance  💰  ",
            "PS1='\\[\\e[43;30m\\] 👑 pro \\[\\e[0;33;40m\\]\\[\\e[40;33m\\] 📁 \\W \\[\\e[0;30;43m\\]\\[\\e[43;30m\\] 💰 \\[\\e[0;33m\\]\\[\\e[0m\\] '");

        add("powerline_neon_tokyo", "东京霓虹 (Tokyo Sharp)", "Powerline尖角",
            " 🗼 tokyo  📁 ~/web  ❯ ",
            "PS1='\\[\\e[44;37m\\] 🗼 tokyo \\[\\e[0;34;45m\\]\\[\\e[45;37m\\] 📁 \\W \\[\\e[0;35m\\]\\[\\e[0m\\] ❯ '");

        add("powerline_angled_flame", "刀锋斜切 (Blade Cut)", "Powerline尖角",
            " ⚔️ blade  📁 ~/src  ⚡  ",
            "PS1='\\[\\e[41;37m\\] ⚔️ blade \\[\\e[0;31;43m\\]\\[\\e[43;30m\\] 📁 \\W \\[\\e[0;33;44m\\]\\[\\e[44;37m\\] ⚡ \\[\\e[0;34m\\]\\[\\e[0m\\] '");

        add("powerline_angled_cyan", "冰刃流光 (Ice Blade)", "Powerline尖角",
            " ❄️ frost  📁 ~/code  ❯ ",
            "PS1='\\[\\e[46;30m\\] ❄️ frost \\[\\e[0;36;44m\\]\\[\\e[44;37m\\] 📁 \\W \\[\\e[0;34m\\]\\[\\e[0m\\] ❯ '");

        add("powerline_monochrome", "黑白撞色 (Black & White)", "Powerline尖角",
            " termux  ~/code  $ ",
            "PS1='\\[\\e[47;30m\\] termux \\[\\e[0;37;40m\\]\\[\\e[40;37m\\] \\W \\[\\e[0;30m\\]\\[\\e[0m\\] $ '");

        add("powerline_matrix_sharp", "数字矩阵 (Matrix Sharp)", "Powerline尖角",
            " 0101  📁 ~/data  ⁕  ",
            "PS1='\\[\\e[42;30m\\] 0101 \\[\\e[0;32;40m\\]\\[\\e[40;32m\\] 📁 \\W \\[\\e[0;30;42m\\]\\[\\e[42;30m\\] ⁕ \\[\\e[0;32m\\]\\[\\e[0m\\] '");

        add("powerline_sunset_sharp", "夕阳尖角 (Sunset Sharp)", "Powerline尖角",
            " 🌇 dusk  📁 ~/night  🌙  ",
            "PS1='\\[\\e[41;37m\\] 🌇 dusk \\[\\e[0;31;43m\\]\\[\\e[43;30m\\] 📁 \\W \\[\\e[0;33;45m\\]\\[\\e[45;37m\\] 🌙 \\[\\e[0;35m\\]\\[\\e[0m\\] '");

        add("powerline_mini_arrow", "极简单色色块", "Powerline尖角",
            " ~/project  ",
            "PS1='\\[\\e[44;37m\\] \\W \\[\\e[0;34m\\]\\[\\e[0m\\] '");

        // -------------------------------------------------------------
        // ⚡ 分类三：现代极客双行流 (Double-Line Modern) - 16 款
        // -------------------------------------------------------------
        add("powerline_modern_double", "双行现代极客 (推荐)", "极客双行",
            "╭─ ⚡ [termux] 📁 ~/workspace\n╰─➤ ",
            "PS1='\\[\\e[1;34m\\]╭─\\[\\e[1;33m\\] ⚡ [\\[\\e[1;32m\\]\\u\\[\\e[1;33m\\]]\\[\\e[1;36m\\] 📁 \\w\\n\\[\\e[1;34m\\]╰─➤\\[\\e[0m\\] '");

        add("double_kali_dragon", "Kali 龙影双行", "极客双行",
            "┌──(kali㉿termux)-[~/recon]\n└─$ ",
            "PS1='\\[\\e[1;34m\\]┌──(\\[\\e[1;31mkali㉿\\u\\[\\e[1;34m\\])-[\\[\\e[1;37m\\]\\w\\[\\e[1;34m\\]]\\n\\[\\e[1;34m\\]└─\\[\\e[1;31m\\]\\$\\[\\e[0m\\] '");

        add("double_neon_cyber", "赛博霓虹双行", "极客双行",
            "╭─ 🌌 ~/code [main]\n╰─► ",
            "PS1='\\[\\e[1;35m\\]╭─ 🌌 \\[\\e[1;36m\\]\\w \\[\\e[1;32m\\][main]\\n\\[\\e[1;35m\\]╰─►\\[\\e[0m\\] '");

        add("double_boxed_frame", "硬核线框双行", "极客双行",
            "┌─[root@termux]-[~/src]\n└─# ",
            "PS1='\\[\\e[1;32m\\]┌─[\\[\\e[1;31m\\]\\u@termux\\[\\e[1;32m\\]]-[\\[\\e[1;36m\\]\\w\\[\\e[1;32m\\]]\\n└─#\\[\\e[0m\\] '");

        add("double_ys_classic", "YS 极客双行", "极客双行",
            "# user @ termux in ~/code [16:30]\n$ ",
            "PS1='\\[\\e[1;33m\\]# \\[\\e[1;36m\\]\\u @ termux \\[\\e[1;35m\\]in \\[\\e[1;32m\\]\\w \\[\\e[0;37m\\][\\A]\\n\\[\\e[1;33m\\]\\$\\[\\e[0m\\] '");

        add("double_bira_skull", "Bira 骷髅黑客", "极客双行",
            "╭─ 💀 [root:~/kernel]\n╰─# ",
            "PS1='\\[\\e[1;31m\\]╭─ 💀 [\\[\\e[1;37m\\]\\u:\\[\\e[1;36m\\]\\w\\[\\e[1;31m\\]]\\n╰─#\\[\\e[0m\\] '");

        add("double_sakura_wave", "樱花和风双行", "极客双行",
            "╭─ 🌸 [sakura] ~/workspace\n╰─❯ ",
            "PS1='\\[\\e[1;35m\\]╭─ 🌸 [sakura] \\[\\e[1;37m\\]\\w\\n\\[\\e[1;35m\\]╰─❯\\[\\e[0m\\] '");

        add("double_cloud_devops", "云原生 DevOps", "极客双行",
            "☁️ (local) 📁 ~/infra [ready]\n└─➜ ",
            "PS1='\\[\\e[1;36m\\]☁️ (local) 📁 \\w \\[\\e[1;32m\\][ready]\\n\\[\\e[1;36m\\]└─➜\\[\\e[0m\\] '");

        add("double_matrix_box", "黑客母体双线", "极客双行",
            "╔═[ neo @ matrix : ~/core ]\n╚══> ",
            "PS1='\\[\\e[1;32m\\]╔═[ neo @ matrix : \\w ]\\n╚══>\\[\\e[0m\\] '");

        add("double_lightning_edge", "雷电特工双行", "极客双行",
            "⚡ root@ubuntu:~/workspace ⚡\n╰─➤ ",
            "PS1='\\[\\e[1;33m\\]⚡ \\[\\e[1;31m\\]\\u@ubuntu:\\[\\e[1;36m\\]\\w \\[\\e[1;33m\\]⚡\\n\\[\\e[1;33m\\]╰─➤\\[\\e[0m\\] '");

        add("double_flame_warrior", "烈焰战神双行", "极客双行",
            "╭── 🔥 [ROOT:~/kernel] ──╮\n╰─❯❯❯ ",
            "PS1='\\[\\e[1;31m\\]╭── 🔥 [\\u:\\w] ──╮\\n╰─❯❯❯\\[\\e[0m\\] '");

        add("double_ocean_ripple", "深蓝海浪双行", "极客双行",
            "╭─ 🌊 [ocean] ~/deep-learning\n╰─≋ ",
            "PS1='\\[\\e[1;34m\\]╭─ 🌊 [ocean] \\[\\e[1;36m\\]\\w\\n\\[\\e[1;34m\\]╰─≋\\[\\e[0m\\] '");

        add("double_taiji_mystic", "阴阳太极双行", "极客双行",
            "╭─ ☯ [taiji] ~/workspace\n╰─☰ ",
            "PS1='\\[\\e[1;37m\\]╭─ ☯ [taiji] \\w\\n╰─☰\\[\\e[0m\\] '");

        add("double_space_odyssey", "太空漫游双行", "极客双行",
            "╭─ 🛸 [cosmos] 📁 ~/stars\n╰─🪐 ",
            "PS1='\\[\\e[1;35m\\]╭─ 🛸 [cosmos] 📁 \\[\\e[1;36m\\]\\w\\n\\[\\e[1;35m\\]╰─🪐\\[\\e[0m\\] '");

        add("double_simple_clean", "极简洁净双行", "极客双行",
            "~/workspace\n❯ ",
            "PS1='\\[\\e[1;36m\\]\\w\\n\\[\\e[1;32m\\]❯\\[\\e[0m\\] '");

        add("double_pure_dollar", "经典双行美元符", "极客双行",
            "~/project\n$ ",
            "PS1='\\[\\e[0;37m\\]\\w\\n$ '");

        // -------------------------------------------------------------
        // 🤖 分类四：AI 开发者专属流 (AI Agent & Coding) - 12 款
        // -------------------------------------------------------------
        add("ai_assistant_lab", "AI 智能工坊 (AI-Lab)", "AI专属",
            "╭─ 🤖 [AI-Lab] 📁 ~/code\n╰─⚡ ",
            "PS1='\\[\\e[1;36m\\]╭─ 🤖 [AI-Lab] 📁 \\[\\e[1;32m\\]\\w\\n\\[\\e[1;33m\\]╰─⚡\\[\\e[0m\\] '");

        add("ai_claude_spark", "Claude Code 智脑", "AI专属",
            "🧠 [claude-code] ~/agent ❯ ",
            "PS1='\\[\\e[1;35m\\]🧠 [claude-code]\\[\\e[1;33m\\] \\W \\[\\e[1;35m\\]❯\\[\\e[0m\\] '");

        add("ai_deepseek_whale", "DeepSeek 蓝鲸代码", "AI专属",
            "🐋 [deepseek] ~/models ❯ ",
            "PS1='\\[\\e[1;34m\\]🐋 [deepseek]\\[\\e[1;36m\\] \\W \\[\\e[1;34m\\]❯\\[\\e[0m\\] '");

        add("ai_openai_codex", "OpenAI Codex 灵感", "AI专属",
            "🤖 <codex> ~/code » ",
            "PS1='\\[\\e[1;32m\\]🤖 <codex>\\[\\e[1;37m\\] \\W \\[\\e[1;32m\\]»\\[\\e[0m\\] '");

        add("ai_pi_agent", "Pi 极速智能体", "AI专属",
            "⚡ π [pi-agent] ~/project ❯ ",
            "PS1='\\[\\e[1;33m\\]⚡ π \\[\\e[1;36m\\][pi-agent]\\[\\e[1;37m\\] \\W \\[\\e[1;33m\\]❯\\[\\e[0m\\] '");

        add("ai_opencode_studio", "OpenCode 极客工作台", "AI专属",
            "💻 [opencode:~/dev] ❯ ",
            "PS1='\\[\\e[1;32m\\]💻 [opencode:\\[\\e[1;36m\\]\\W\\[\\e[1;32m\\]] ❯\\[\\e[0m\\] '");

        add("ai_gemini_spark", "Gemini 双子星韵", "AI专属",
            "♊ [gemini] ~/stars ✦ ",
            "PS1='\\[\\e[1;34m\\]♊ [gemini]\\[\\e[1;35m\\] \\W ✦\\[\\e[0m\\] '");

        add("ai_ollama_llama", "Ollama 本地羊驼", "AI专属",
            "🦙 [ollama:local] ~/llm ❯ ",
            "PS1='\\[\\e[1;33m\\]🦙 [ollama:local]\\[\\e[1;32m\\] \\W ❯\\[\\e[0m\\] '");

        add("ai_aider_pair", "Aider 结对编程", "AI专属",
            "🤝 [aider:pair] ~/repo ❯ ",
            "PS1='\\[\\e[1;36m\\]🤝 [aider:pair]\\[\\e[1;37m\\] \\W ❯\\[\\e[0m\\] '");

        add("ai_copilot_wing", "Copilot 飞行副驾", "AI专属",
            "✈️ [copilot] ~/fly » ",
            "PS1='\\[\\e[1;34m\\]✈️ [copilot]\\[\\e[1;37m\\] \\W »\\[\\e[0m\\] '");

        add("ai_cursor_target", "Cursor 精准光标", "AI专属",
            "🎯 [cursor] ~/focus ⯈ ",
            "PS1='\\[\\e[1;36m\\]🎯 [cursor]\\[\\e[1;32m\\] \\W ⯈\\[\\e[0m\\] '");

        add("ai_matrix_agent", "Agent 矩阵特工", "AI专属",
            "╭─ 🕶️ [agent-01] 📁 ~/target\n╰─⚡ ",
            "PS1='\\[\\e[1;32m\\]╭─ 🕶️ [agent-01] 📁 \\w\\n╰─⚡\\[\\e[0m\\] '");

        // -------------------------------------------------------------
        // 🐧 分类五：Linux / UNIX 经典发行版官方流 - 12 款
        // -------------------------------------------------------------
        add("os_ubuntu_classic", "Ubuntu 官方经典", "经典系统",
            "ubuntu@localhost:~/code$ ",
            "PS1='\\[\\e[1;32m\\]\\u@localhost\\[\\e[0m\\]:\\[\\e[1;34m\\]\\w\\[\\e[0m\\]\\$ '");

        add("os_arch_official", "Arch Linux 官方", "经典系统",
            "[user@archlinux ~]$ ",
            "PS1='[\\u@archlinux \\W]\\$ '");

        add("os_debian_stable", "Debian 经典红白", "经典系统",
            "user@debian:~/src$ ",
            "PS1='\\[\\e[1;31m\\]\\u@debian\\[\\e[0m\\]:\\[\\e[1;34m\\]\\w\\[\\e[0m\\]\\$ '");

        add("os_alpine_docker", "Alpine 容器极简", "经典系统",
            "alpine:~/app# ",
            "PS1='\\[\\e[1;34m\\]alpine\\[\\e[0m\\]:\\w# '");

        add("os_fedora_blue", "Fedora 蓝色经典", "经典系统",
            "[user@fedora ~/work]$ ",
            "PS1='[\\[\\e[1;34m\\]\\u@fedora\\[\\e[0m\\] \\W]\\$ '");

        add("os_gentoo_purple", "Gentoo 硬核编译", "经典系统",
            "gentoo ~/code # ",
            "PS1='\\[\\e[1;35m\\]gentoo\\[\\e[0m\\] \\w # '");

        add("os_centos_redhat", "CentOS / RHEL", "经典系统",
            "[root@centos ~]# ",
            "PS1='[\\u@centos \\W]\\$ '");

        add("os_macos_darwin", "macOS Terminal", "经典系统",
            "MacBook-Pro:workspace user$ ",
            "PS1='MacBook-Pro:\\W \\u\\$ '");

        add("os_freebsd_daemon", "FreeBSD 小恶魔", "经典系统",
            "daemon@freebsd:~ % ",
            "PS1='\\u@freebsd:\\w % '");

        add("os_retro_dos", "复古 MS-DOS", "经典系统",
            "C:\\TERMUX\\CODE> ",
            "PS1='C:\\\\TERMUX\\\\\\W> '");

        add("os_c64_vintage", "Commodore 64 复古", "经典系统",
            "READY.\n> ",
            "PS1='READY.\\n> '");

        add("os_nixos_snowflake", "NixOS 纯函数雪花", "经典系统",
            "❄️ [nix-shell:~/code]$ ",
            "PS1='❄️ [nix-shell:\\W]\\$ '");

        // -------------------------------------------------------------
        // ✨ 分类六：极简纯粹符号流 (Minimal & Clean) - 12 款
        // -------------------------------------------------------------
        add("min_starship_arrow", "Starship 经典纯粹箭头", "极简符号",
            "~/workspace ❯ ",
            "PS1='\\[\\e[1;36m\\]\\W \\[\\e[1;32m\\]❯\\[\\e[0m\\] '");

        add("min_zsh_green", "Zsh Robbyrussell 经典", "极简符号",
            "➜ ~/project ",
            "PS1='\\[\\e[1;32m\\]➜ \\[\\e[1;36m\\]\\W\\[\\e[0m\\] '");

        add("min_lambda_lisp", "Lambda λ 函数极客", "极简符号",
            "~/code λ ",
            "PS1='\\[\\e[1;35m\\]\\W λ\\[\\e[0m\\] '");

        add("min_delta_triangle", "Delta Δ 增量三角", "极简符号",
            "[termux] Δ ",
            "PS1='[\\[\\e[1;36m\\]\\u\\[\\e[0m\\]] \\[\\e[1;33m\\]Δ\\[\\e[0m\\] '");

        add("min_fish_chevron", "Fish Shell 尖角", "极简符号",
            "~/src » ",
            "PS1='\\[\\e[1;34m\\]\\W \\[\\e[1;33m\\]»\\[\\e[0m\\] '");

        add("min_double_chevron", "双层推进 »»", "极简符号",
            "~/dev »» ",
            "PS1='\\[\\e[1;32m\\]\\W \\[\\e[1;36m\\]»»\\[\\e[0m\\] '");

        add("min_diamond_spark", "璀璨星钻 ⟡", "极简符号",
            "~/ai-lab ⟡ ",
            "PS1='\\[\\e[1;35m\\]\\W ⟡\\[\\e[0m\\] '");

        add("min_four_star", "四角星芒 ✦", "极简符号",
            "~/lab ✦ ",
            "PS1='\\[\\e[1;33m\\]\\W ✦\\[\\e[0m\\] '");

        add("min_target_dot", "同心圆靶 ⊙", "极简符号",
            "~/hub ⊙ ",
            "PS1='\\[\\e[1;36m\\]\\W ⊙\\[\\e[0m\\] '");

        add("min_pure_unix", "纯粹 UNIX 美元符", "极简符号",
            "$ ",
            "PS1='\\$ '");

        add("min_lightning_bolt", "金色闪电 ⚡", "极简符号",
            "⚡ ~/project ",
            "PS1='\\[\\e[1;33m\\]⚡ \\[\\e[1;36m\\]\\W\\[\\e[0m\\] '");

        add("min_bullet_point", "圆点微标 •", "极简符号",
            "~/code • ",
            "PS1='\\[\\e[1;37m\\]\\W \\[\\e[1;32m\\]•\\[\\e[0m\\] '");
    }

    private static void add(String id, String name, String category, String previewText, String ps1Script) {
        S_PROMPTS.add(new PromptItem(id, name, category, previewText, ps1Script));
    }

    public static List<PromptItem> getAllPrompts() {
        return new ArrayList<>(S_PROMPTS);
    }

    public static List<String> getCategories() {
        List<String> categories = new ArrayList<>();
        categories.add("全部");
        categories.add("圆角胶囊");
        categories.add("Powerline尖角");
        categories.add("极客双行");
        categories.add("AI专属");
        categories.add("经典系统");
        categories.add("极简符号");
        return categories;
    }

    public static PromptItem getPromptById(String id) {
        for (PromptItem item : S_PROMPTS) {
            if (item.id.equals(id)) return item;
        }
        return S_PROMPTS.get(0);
    }

    public static String getCurrentPromptId(Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return sp.getString(KEY_CURRENT_PROMPT_ID, DEFAULT_PROMPT_ID);
    }

    public static void setCurrentPromptId(Context context, String id) {
        SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        sp.edit().putString(KEY_CURRENT_PROMPT_ID, id).apply();
    }

    public static boolean applyPromptStyle(Context context, TermuxActivity activity, PromptItem item) {
        if (item == null) return false;

        // 1. 持久化存储所选 ID
        setCurrentPromptId(context, item.id);

        String promptContent = "#!/bin/bash\n# Generated by Termux+ Prompt Manager\n" + item.ps1Script + "\nexport PS1\n";
        byte[] promptBytes = promptContent.getBytes(StandardCharsets.UTF_8);

        try {
            // A. 写入 Termux 本地配置 (~/.termux/prompt.sh 与 $PREFIX/etc/bash.bashrc)
            File homeDir = new File(context.getFilesDir(), "home");
            File termuxDir = new File(homeDir, ".termux");
            if (!termuxDir.exists()) termuxDir.mkdirs();
            File termuxPromptFile = new File(termuxDir, "prompt.sh");
            writeFile(termuxPromptFile, promptBytes);

            File bashrcFile = new File(homeDir, ".bashrc");
            appendOnce(bashrcFile, "\n[ -f ~/.termux/prompt.sh ] && . ~/.termux/prompt.sh\n", "prompt.sh");

            File bashProfile = new File(homeDir, ".bash_profile");
            appendOnce(bashProfile, "\n[ -f ~/.termux/prompt.sh ] && . ~/.termux/prompt.sh\n", "prompt.sh");

            File prefixEtc = new File(context.getFilesDir(), "usr/etc");
            if (prefixEtc.exists()) {
                File globalBashrc = new File(prefixEtc, "bash.bashrc");
                appendOnce(globalBashrc, "\n[ -f " + termuxPromptFile.getAbsolutePath() + " ] && . " + termuxPromptFile.getAbsolutePath() + "\n", "prompt.sh");
            }

            // B. 深度穿透写入 Ubuntu (proot-distro) 容器内部
            List<File> distroRoots = UbuntuAppManager.getInstalledDistroRoots(context);
            for (File distro : distroRoots) {
                // 1. 写入 /etc/profile.d/termux_prompt.sh (Ubuntu 所有登录 Shell 必定加载)
                File profileD = new File(distro, "etc/profile.d");
                if (!profileD.exists()) profileD.mkdirs();
                File uPromptFile = new File(profileD, "termux_prompt.sh");
                writeFile(uPromptFile, promptBytes);

                // 2. 写入 /etc/bash.bashrc (Ubuntu 全局非登录 Shell 必读)
                File uBashrc = new File(distro, "etc/bash.bashrc");
                if (uBashrc.exists()) {
                    appendOnce(uBashrc, "\n[ -f /etc/profile.d/termux_prompt.sh ] && . /etc/profile.d/termux_prompt.sh\n", "termux_prompt.sh");
                }

                // 3. 写入 /root/.bashrc (确保放在最末尾，优先级最高，覆盖 termux-webui)
                File uRootBashrc = new File(distro, "root/.bashrc");
                if (uRootBashrc.exists()) {
                    appendOnce(uRootBashrc, "\n# Termux+ Prompt Style\n" + item.ps1Script + "\nexport PS1\n", "Termux+ Prompt Style");
                }
            }

            // C. 毫秒级热加载：直接向活跃终端发送 export PS1 命令，绝对零语法错误
            if (activity != null && activity.getTermuxService() != null) {
                List<TermuxSession> termuxSessions = activity.getTermuxService().getTermuxSessions();
                if (termuxSessions != null) {
                    for (TermuxSession tSession : termuxSessions) {
                        TerminalSession ts = tSession.getTerminalSession();
                        if (ts != null && ts.isRunning()) {
                            // 先换行 + Ctrl+C 取消残余输入，然后直接设置 PS1 并 export，当前终端立即刷新变身！
                            String reloadCmd = "\n\u0003" + item.ps1Script + "; export PS1\n";
                            ts.write(reloadCmd);
                        }
                    }
                }
            }
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    private static void writeFile(File file, byte[] data) {
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(data);
        } catch (Exception ignored) {
        }
    }

    private static void appendOnce(File file, String snippet, String checkKeyword) {
        if (!file.exists()) {
            writeFile(file, snippet.getBytes(StandardCharsets.UTF_8));
            return;
        }
        try {
            byte[] bytes = new byte[(int) file.length()];
            try (java.io.FileInputStream fis = new java.io.FileInputStream(file)) {
                fis.read(bytes);
            }
            String existing = new String(bytes, StandardCharsets.UTF_8);
            if (!existing.contains(checkKeyword)) {
                try (FileOutputStream fos = new FileOutputStream(file, true)) {
                    fos.write(snippet.getBytes(StandardCharsets.UTF_8));
                }
            }
        } catch (Exception ignored) {
        }
    }
}

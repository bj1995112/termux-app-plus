#!/bin/sh
# ==============================================================================
# Termux+ Dynamic Prompt Engine (Prompt Engine 2.0)
# 零输入注入 · 动态热加载 · 跨环境（Termux 原生 & Ubuntu 容器）双向兼容
# ==============================================================================

# 定位配置文件（优先当前环境 HOME，其次 Termux 宿主路径）
__termux_find_prompt_conf() {
    if [ -r "$HOME/.termux/prompt.conf" ]; then
        echo "$HOME/.termux/prompt.conf"
    elif [ -r "/data/data/com.termux/files/home/.termux/prompt.conf" ]; then
        echo "/data/data/com.termux/files/home/.termux/prompt.conf"
    else
        echo ""
    fi
}

# 动态求值并应用 PS1
__termux_prompt_apply() {
    local __ret=$? # 保留上一条命令的退出状态码
    local __conf
    __conf=$(__termux_find_prompt_conf)

    local theme="starship"
    local color="cyan"
    local ansi="45"

    if [ -n "$__conf" ] && [ -r "$__conf" ]; then
        . "$__conf"
    fi


    # 备份原始 PS1
    if [ -z "$__TERMUX_ORIG_PS1" ]; then
        __TERMUX_ORIG_PS1="$PS1"
    fi

    # ANSI 256 色解析
    case "$color" in
        cyan)   ansi="45" ;;
        blue)   ansi="81" ;;
        purple) ansi="141" ;;
        pink)   ansi="213" ;;
        green)  ansi="48" ;;
        yellow) ansi="220" ;;
        orange) ansi="208" ;;
        red)    ansi="203" ;;
        white)  ansi="255" ;;
        *)      ansi="45" ;;
    esac

    local C="\[\e[38;5;${ansi}m\]"
    local CG="\[\e[38;5;48m\]"
    local CR="\[\e[38;5;203m\]"
    local CD="\[\e[38;5;244m\]"
    local B="\[\e[1m\]"
    local R="\[\e[0m\]"

    # 状态码符号（成功绿 ❯，失败红 ❯）
    local STAT_SYM
    if [ $__ret -eq 0 ]; then
        STAT_SYM="${CG}❯${R}"
    else
        STAT_SYM="${CR}❯${R}"
    fi

    case "$theme" in
        starship)
            # 现代星际双行：顶部目录与状态，底部输入箭头
            PS1="${C}┌──[${B}\w${R}${C}]${R}\n${C}└──${R} ${STAT_SYM} "
            ;;
        galaxy)
            # 星系穿梭 (Galaxy / Warp 风格)
            PS1="${C}╭─ 🌌 \u in ${B}\w${R}\n${C}╰─❯${R} "
            ;;
        gitflow)
            # Git 开发者徽章
            PS1="${C}┌──(${B}\W${R}${C}) - [${CG}🌿 main${R}${C}]\n${C}└─ λ${R} "
            ;;
        pulse)
            # 状态码脉冲
            PS1="${C}╭─[ \t ] ⚡ [${B}\w${R}${C}]\n${C}╰─➤${R} "
            ;;
        capsule)
            # 胶囊双联
            PS1="${C}┏━ [ ${B}\u@\h${R}${C} ] ━━ ( ${B}\W${R}${C} )\n${C}┗━▶${R} "
            ;;
        hud)
            # HUD 科幻状态栏（经典受欢迎的 [user@host] ━➤ ）
            PS1="${C}[\u@\h] ━➤ ${R}"
            ;;
        matrix)
            # 黑客帝国矩阵
            PS1="[SYS::${CG}\u@\h${R}${C}] # ${B}\w${R}${C} >> ${R}"
            ;;
        mecha)
            # 生化机甲 HUD
            PS1="${C}◈ [STATUS:${CG}OK${R}${C}] ━◆ [${B}\w${R}${C}] ━➤ ${R}"
            ;;
        blade)
            # 霓虹切割
            PS1="${C}◢◤ ${B}TERMUX${R}${C} ◢◤ \w ◢ ${R}"
            ;;
        arrow)
            # 优雅单行尾翼
            PS1="${C}\w ╰─➤ ${R}"
            ;;
        purearrow)
            # 纯粹单箭头
            PS1="${B}\W${R} ${C}❯${R} "
            ;;
        wave)
            # 波浪单行
            PS1="~ ∿ ${C}\w${R} ∿ "
            ;;
        lightning)
            # 闪电极速
            PS1="⚡ [${C}\u${R}] ${B}\w${R} » "
            ;;
        kali)
            # Kali Linux 经典渗透双行
            PS1="${C}┌──(${CD}\u㉿\h${C})-[${B}\w${R}${C}]\n${C}└─\$ ${R}"
            ;;
        powerline)
            # Powerline 极客切角风格
            PS1="${C}\u ${CD} ${C}\W ${CD}${R} ${STAT_SYM} "
            ;;
        cyber)
            # 赛博朋克重型霓虹
            PS1="${C}━━━[${B}\w${R}${C}]━━➤ ${R}"
            ;;
        minimal)
            # 极简单行箭头
            if [ $__ret -eq 0 ]; then
                PS1="${C}\W ${CG}➜ ${R}"
            else
                PS1="${C}\W ${CR}➜ ${R}"
            fi
            ;;
        ubuntu)
            # Ubuntu 官方经典
            PS1="${CG}\u@\h${R}:${C}\w${R}\$ "
            ;;
        arch)
            # Arch Linux 经典
            PS1="[${C}\u@archlinux${R} ${B}\w${R}]# "
            ;;
        debian)
            # Debian 经典螺旋
            PS1="🌀 (${CD}debian${R}) ${C}\u@\h:\w\$ ${R}"
            ;;
        gentoo)
            # Gentoo 紫黑极客
            PS1="[${C}gentoo${R}] ${B}\w${R} % "
            ;;
        dos)
            # 经典 MS-DOS 复古
            PS1="${C}C:\\\W> ${R}"
            ;;
        neon)
            # 双行方括号极客
            PS1="${C}╭─[ ${B}\u@\h${R}${C} ] - [ ${B}\w${R}${C} ]\n${C}╰──➤ ${R}"
            ;;
        neko)
            # 软萌猫爪
            PS1="🐾 (${C}ฅ'ω'ฅ${R}) ${B}\w${R} 🌸 "
            ;;
        rpg)
            # 像素复古 RPG
            PS1="[LV.99 ${C}HERO${R}] ⚔️ ${B}\W${R} ❯ "
            ;;
        default)
            # 系统默认经典结构 + 用户自选色彩高亮联动
            if [ -f /etc/debian_version ] || [ -n "$debian_chroot" ]; then
                PS1="${debian_chroot:+($debian_chroot)}${C}\u@\h${R}:${C}${B}\w${R}\$ "
            else
                PS1="${C}\w${R} \$ "
            fi
            ;;
        *)
            PS1="${C}┌──[${B}\w${R}${C}]\n${C}└──${R} ${STAT_SYM} "
            ;;
    esac

    export PS1
    return $__ret
}

# 挂接动态执行钩子（纯非侵入式）
if [ -n "$BASH_VERSION" ]; then
    case "$PROMPT_COMMAND" in
        *__termux_prompt_apply*) ;;
        *)
            if [ -n "$PROMPT_COMMAND" ]; then
                PROMPT_COMMAND="__termux_prompt_apply; $PROMPT_COMMAND"
            else
                PROMPT_COMMAND="__termux_prompt_apply"
            fi
            ;;
    esac
    # 绑定无害刷新键（\e[99~ 毫秒级重绘，绝不污染输入行）
    bind -m emacs-standard -x '"\e[99~":__termux_prompt_apply' 2>/dev/null || true
    bind -m vi-insertion -x '"\e[99~":__termux_prompt_apply' 2>/dev/null || true
elif [ -n "$ZSH_VERSION" ]; then
    autoload -Uz add-zsh-hook 2>/dev/null
    add-zsh-hook precmd __termux_prompt_apply 2>/dev/null
fi

# 启动时立刻执行一次，确保初始界面秒显正确提示符
__termux_prompt_apply 2>/dev/null || true

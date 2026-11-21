package com.rembrowser.rembrowse.terminal

/**
 * Suggests how a command being typed might end, fish-style: the commands the user ran before
 * (most recent first), then common ones. Pure, so it can be tested on the JVM.
 */
object CommandSuggestions {

    /** How many commands are remembered. */
    const val MAX_HISTORY = 1_000

    /** The longest command remembered; longer ones are usually pasted scripts. */
    private const val MAX_COMMAND_LENGTH = 300

    /**
     * The whole command [typed] probably continues into, or null. Only a strictly longer
     * command that starts with exactly what was typed counts; nothing is suggested for blank
     * input.
     */
    fun suggest(typed: String, history: List<String>): String? {
        if (typed.isBlank() || typed.length > MAX_COMMAND_LENGTH) return null
        history.firstOrNull { it.length > typed.length && it.startsWith(typed) }?.let { return it }
        return COMMON.firstOrNull { it.length > typed.length && it.startsWith(typed) }
    }

    /**
     * [history] with [command] added at the front, as shells keep it; null when it is not
     * remembered: blank, too long, or typed with a leading space (the shell convention for
     * "keep this out of the history", for commands with secrets in them).
     */
    fun remember(command: String, history: List<String>): List<String>? {
        if (command.isBlank() || command.startsWith(" ") || command.length > MAX_COMMAND_LENGTH) return null
        if (command.any { it.isISOControl() }) return null
        val trimmed = command.trimEnd()
        return (listOf(trimmed) + history.filterNot { it == trimmed }).take(MAX_HISTORY)
    }

    /** Common commands and the forms they are usually typed in, most common first within each tool. */
    val COMMON: List<String> = listOf(
        // Moving around and looking
        "ls -la", "ls -lh", "ls", "cd ..", "cd ~", "cd -", "pwd", "clear", "exit",
        "cat /etc/os-release", "less", "tail -f /var/log/syslog", "tail -n 100", "head -n 20",
        "du -sh *", "du -sh .", "df -h", "free -h", "uptime", "whoami", "hostname -I", "history | tail -n 30",
        "find . -name ", "find . -type f -name ", "grep -rn ", "grep -i ", "which ",
        "mkdir -p ", "cp -r ", "mv ", "rm -rf ", "touch ", "chmod +x ", "chown -R ", "ln -s ",
        "nano ", "vim ", "watch -n 1 ",
        // Packages and the system
        "sudo apt update", "sudo apt update && sudo apt upgrade -y", "sudo apt upgrade -y", "sudo apt install ",
        "sudo apt autoremove", "apt list --upgradable", "sudo reboot", "sudo shutdown -h now", "sudo -i", "sudo su",
        "sudo systemctl status ", "sudo systemctl restart ", "sudo systemctl start ", "sudo systemctl stop ",
        "sudo systemctl enable --now ", "sudo systemctl daemon-reload", "systemctl status ", "systemctl --user status ",
        "sudo journalctl -u ", "journalctl -f", "journalctl -xe", "sudo journalctl -fu ", "dmesg | tail",
        "htop", "top", "ps aux | grep ", "kill -9 ", "pkill ",
        "sudo ufw status", "sudo ufw allow ", "ip a", "ip route", "ss -tulpn", "sudo ss -tulpn",
        "ping -c 4 ", "curl -I ", "curl -sSL ", "wget ", "dig ", "nslookup ", "traceroute ",
        "tailscale status", "sudo tailscale up",
        // Git
        "git status", "git pull", "git push", "git log --oneline -20", "git log --oneline --graph --all",
        "git diff", "git diff --staged", "git add -A", "git add .", "git commit -m \"", "git commit --amend",
        "git checkout ", "git checkout -b ", "git switch ", "git switch -c ", "git branch -a", "git fetch --all --prune",
        "git stash", "git stash pop", "git reset --hard", "git rebase -i HEAD~", "git clone ", "git remote -v",
        // Containers
        "docker ps", "docker ps -a", "docker compose up -d", "docker compose down", "docker compose pull",
        "docker compose logs -f", "docker compose ps", "docker compose restart", "docker logs -f ",
        "docker exec -it ", "docker images", "docker system prune -af", "docker stats", "docker pull ",
        "kubectl get pods", "kubectl get pods -A", "kubectl describe pod ", "kubectl logs -f ", "kubectl get svc",
        "kubectl apply -f ", "kubectl get nodes",
        // Sessions, files over SSH, archives
        "tmux attach", "tmux new -s ", "tmux ls", "screen -r",
        "scp ", "rsync -avz ", "ssh ", "ssh-keygen -t ed25519", "ssh-copy-id ",
        "tar -xzf ", "tar -czf ", "unzip ", "zip -r ",
        // Languages and tools
        "python3 ", "python3 -m venv .venv", "source .venv/bin/activate", "pip install -r requirements.txt", "pip install ",
        "npm install", "npm run dev", "npm run build", "npm test", "npx ", "node ",
        "make", "make install", "cargo build", "cargo run", "go run .", "go build ./..."
    )
}

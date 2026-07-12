import paramiko
import getpass
import sys

def print_step(msg):
    print(f"\n[*] {msg}...")

def run_cmd(ssh, cmd, user, password, hide_output=False):
    """Executes a command over SSH. Handles sudo securely via stdin if not root."""
    if user != 'root':
        cmd = f"sudo -S -p '' bash -c '{cmd}'"
    
    stdin, stdout, stderr = ssh.exec_command(cmd)
    
    if user != 'root':
        stdin.write(password + '\n')
        stdin.flush()
        
    exit_status = stdout.channel.recv_exit_status()
    out = stdout.read().decode('utf-8').strip()
    err = stderr.read().decode('utf-8').strip()
    
    if not hide_output and out:
        print(out)
    if exit_status != 0 and err:
        print(f"[!] Error executing command: {err}")
        
    return exit_status, out, err

def write_file_remote(ssh, filepath, content, user, password):
    """Writes multi-line text to a file on the remote server securely using sudo."""
    safe_content = content.replace("'", "'\\''")
    cmd = f"cat << 'EOF' > {filepath}\n{safe_content}\nEOF"
    run_cmd(ssh, cmd, user, password, hide_output=True)

def main():
    print("===================================================================")
    print("           VayDNS Server Cross-Platform Setup Script")
    print("===================================================================")
    print("SUPPORTED OS:")
    print("- RHEL Distros (Rocky Linux 9/10, Alma Linux 9/10, etc.)")
    print("- Debian Distros (Ubuntu 22.04, Ubuntu 24.04, etc.)")
    print("\nREQUIREMENTS:")
    print("1. You MUST create an 'A' record and an 'NS' record in your domain registrar.")
    print("2. You need the root password or a user with sudo privileges.")
    print("===================================================================")
    
    ack = input("Have you set up your DNS records? Type 'y' to continue: ").strip().lower()
    if ack != 'y':
        print("Please configure your DNS records first. Exiting.")
        sys.exit(0)

    # Collect inputs
    print("\n--- Server Details ---")
    host = input("IPv4 address of the server: ").strip()
    
    # SSH Port handling
    ssh_port_input = input("SSH Port (default: 22): ").strip()
    ssh_port = int(ssh_port_input) if ssh_port_input.isdigit() else 22
    
    user = input("User (default: root): ").strip() or "root"
    password = getpass.getpass("Password: ")
    domain = input("Tunnel domain name (e.g., t.example.com): ").strip()
    record_type = input("Record type (caa, null, txt) [default: caa]: ").strip().lower() or "caa"

    # Connect to Server
    print_step(f"Connecting to {host}:{ssh_port} as {user}")
    ssh = paramiko.SSHClient()
    ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    
    try:
        ssh.connect(hostname=host, port=ssh_port, username=user, password=password, timeout=10)
        print("[+] Successfully connected!")
    except Exception as e:
        print(f"[-] SSH Connection failed: {e}")
        sys.exit(1)

    # Detect Remote Operating System
    print_step("Detecting remote operating system architecture")
    _, os_info, _ = run_cmd(ssh, "cat /etc/os-release", user, password, hide_output=True)
    os_info_lower = os_info.lower()
    
    is_ubuntu = "ubuntu" in os_info_lower or "debian" in os_info_lower
    
    if is_ubuntu:
        print("[+] Detected Environment: Ubuntu/Debian Base")
        dante_config_path = "/etc/danted.conf"
        dante_service = "danted"
    else:
        print("[+] Detected Environment: RHEL/Rocky/Alma Base")
        dante_config_path = "/etc/sockd.conf"
        dante_service = "sockd"

    # 1. System Updates & Packages
    print_step("Updating system package repositories and installing core dependencies")
    if is_ubuntu:
        run_cmd(ssh, "export DEBIAN_FRONTEND=noninteractive && apt-get update -y", user, password)
        run_cmd(ssh, "export DEBIAN_FRONTEND=noninteractive && apt-get install danted ufw curl sed tcpdump -y", user, password)
    else:
        run_cmd(ssh, "dnf update -y", user, password)
        run_cmd(ssh, "dnf install epel-release -y", user, password)
        run_cmd(ssh, "dnf install dante-server firewalld policycoreutils-python-utils curl tcpdump -y", user, password)

    # 2. Firewall Configuration (UFW vs Firewalld)
    print_step("Configuring target platform firewall policies")
    if is_ubuntu:
        # Idiomatic Ubuntu Firewall Configuration using UFW + Native iptables routing tables
        run_cmd(ssh, "systemctl start ufw", user, password)
        run_cmd(ssh, "systemctl enable ufw", user, password)
        
        # Explicitly prevent SSH lockouts using the custom port
        run_cmd(ssh, f"ufw allow {ssh_port}/tcp", user, password)  
        
        # Insert NAT rules safely at line 1 of UFW's before.rules structure
        nat_rule_cmd = (
            "if ! grep -q '*nat' /etc/ufw/before.rules; then "
            "sed -i '1i *nat\\n:PREROUTING ACCEPT [0:0]\\n-A PREROUTING -p udp --dport 53 -j REDIRECT --to-ports 5300\\nCOMMIT\\n' /etc/ufw/before.rules; "
            "fi"
        )
        run_cmd(ssh, nat_rule_cmd, user, password)
        run_cmd(ssh, "ufw default deny incoming", user, password)
        run_cmd(ssh, "ufw --force enable", user, password)
        run_cmd(ssh, "ufw reload", user, password)
    else:
        # Idiomatic RHEL Firewall Configuration using Firewalld
        run_cmd(ssh, "systemctl start firewalld", user, password)
        run_cmd(ssh, "systemctl enable firewalld", user, password)
        
        # Explicitly prevent SSH lockouts using the custom port
        run_cmd(ssh, f"firewall-cmd --permanent --add-port={ssh_port}/tcp", user, password)
        
        run_cmd(ssh, "firewall-cmd --permanent --add-forward-port=port=53:proto=udp:toport=5300", user, password)
        run_cmd(ssh, "firewall-cmd --permanent --zone=public --set-target=DROP", user, password)
        run_cmd(ssh, "firewall-cmd --permanent --add-masquerade", user, password)
        run_cmd(ssh, "firewall-cmd --reload", user, password)

    # 3. Create Service User
    print_step("Validating and creating system service accounts")
    run_cmd(ssh, "id -u vaydns &>/dev/null || useradd -r -M -s /bin/false -c 'vaydns service user' -d /nonexistent vaydns", user, password)

    # 4. Download Binary & Generate Keys
    print_step("Downloading deployment binary and generating secure cryptographic keys")
    binary_url = "https://raw.githubusercontent.com/Starling226/phoenix-vpn/main/scripts/vaydns-server"
    
    setup_cmds = f"""
    cd /tmp
    curl -sL {binary_url} -o vaydns-server
    chmod +x vaydns-server
    ./vaydns-server -gen-key -privkey-file server.key -pubkey-file server.pub
    mkdir -p /etc/vaydns
    mv server.key server.pub /etc/vaydns/
    chown -R vaydns:vaydns /etc/vaydns
    mv vaydns-server /usr/local/bin/
    chmod 755 /usr/local/bin/vaydns-server
    """
    run_cmd(ssh, setup_cmds, user, password)

    # Apply Mandatory MAC Security Constraints Context only on Enterprise Linux Systems
    if not is_ubuntu:
        print_step("Applying target SELinux security context rules")
        run_cmd(ssh, 'semanage fcontext -a -t bin_t "/usr/local/bin/vaydns-server"', user, password)
        run_cmd(ssh, "restorecon -v /usr/local/bin/vaydns-server", user, password)

    # 5. Create SystemD Service
    print_step("Writing systemd architectural service blocks")
    vaydns_service_content = f"""[Unit]
Description=VayDNS Tunnel Server
After=network.target
Wants=network.target

[Service]
Type=simple
User=vaydns
Group=vaydns
AmbientCapabilities=CAP_NET_BIND_SERVICE
CapabilityBoundingSet=CAP_NET_BIND_SERVICE
ExecStart=/usr/local/bin/vaydns-server -udp :5300 -privkey-file /etc/vaydns/server.key -mtu 1232 -record-type {record_type} -idle-timeout 10s -keepalive 2s -domain {domain} -upstream 127.0.0.1:8000
Restart=always
RestartSec=5
KillMode=mixed
TimeoutStopSec=5

# Security settings
NoNewPrivileges=true
ProtectSystem=strict
ProtectHome=true
ReadOnlyPaths=/
ReadWritePaths=/etc/vaydns
PrivateTmp=true
ProtectKernelTunables=true
ProtectKernelModules=true
ProtectControlGroups=true

[Install]
WantedBy=multi-user.target"""
    
    write_file_remote(ssh, "/etc/systemd/system/vaydns-server.service", vaydns_service_content, user, password)

    # 6. Configure Dante Proxy Matrix
    print_step(f"Deploying custom configurations to {dante_config_path}")
    run_cmd(ssh, f"mv {dante_config_path} {dante_config_path}.1 || true", user, password)
    
    sockd_content = """logoutput: stderr
internal: 127.0.0.1 port = 8000
external: eth0
socksmethod: none
clientmethod: none
#user.privileged: root
#user.unprivileged: nobody

client pass {
        from: 127.0.0.1/32 to: 0.0.0.0/0
        log: connect error
}

socks pass {
        from: 127.0.0.1/32 to: 0.0.0.0/0
        protocol: tcp udp
        log: connect error
}"""
    write_file_remote(ssh, dante_config_path, sockd_content, user, password)

    # 7. Start Services
    print_step(f"Booting up and enabling backend Dante proxy ({dante_service})")
    run_cmd(ssh, "systemctl daemon-reload", user, password)
    run_cmd(ssh, f"systemctl start {dante_service}", user, password)
    run_cmd(ssh, f"systemctl enable {dante_service}", user, password)
    
    print_step("Booting up and enabling VayDNS core tunnel core infrastructure")
    run_cmd(ssh, "systemctl start vaydns-server", user, password)
    run_cmd(ssh, "systemctl enable vaydns-server", user, password)

    # 8. Retrieve Data
    print_step("Fetching generation keys and diagnostics reports from deployment")
    _, pubkey_raw, _ = run_cmd(ssh, "cat /etc/vaydns/server.pub", user, password, hide_output=True)
    pubkey = pubkey_raw.strip()
    
    _, status_out, _ = run_cmd(ssh, "systemctl status vaydns-server --no-pager", user, password, hide_output=True)
    
    ssh.close()

    # 9. Output Results to User
    print("\n===================================================================")
    print("                    SERVER STATUS REPORT")
    print("===================================================================")
    print(status_out)
    print("===================================================================")
    
    if not pubkey:
        print("\n[!] Error: Could not retrieve the public key. Check the server logs.")
        sys.exit(1)

    client_config_url = f"dnst://{domain}/vaydns/socks5?pubkey={pubkey}&record-type={record_type}&clientid-size=2&keepalive=2s&idle-timeout=10s#vaydns"

    print("\n✅ DEPLOYMENT AND CONFIGURATION LINK GENERATION SUCCESSFUL!")
    print("\n--- YOUR VAYDNS ANDROID READY STRING ---")
    print(client_config_url)
    print("\nImport Method:")
    print("1. Launch VayDNS Android.")
    print("2. Open context operations menu (top-right dashboard).")
    print("3. Choose 'Import' and commit this string layout onto your configuration profile engine.")

if __name__ == "__main__":
    main()

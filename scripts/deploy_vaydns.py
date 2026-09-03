import os
import subprocess
import paramiko
import getpass
import sys
import random
import string

def print_step(msg):
    print(f"\n[*] {msg}...")

def generate_password(length=12):
    """Generates a secure 12-character alphanumeric password."""
    chars = string.ascii_letters + string.digits
    return ''.join(random.choice(chars) for _ in range(length))

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

def get_current_vaydns_params(ssh, user, password):
    """Fetches and parses the current vaydns-server.service parameters."""
    _, out, _ = run_cmd(ssh, "cat /etc/systemd/system/vaydns-server.service || true", user, password, hide_output=True)
    
    params = {
        'domain': '',
        'record_type': 'caa',
        'idle_timeout': '10s',
        'keepalive': '2s',
        'vaydns_port': '5300',
        'upstream_port': '8000',
        'mtu': '1232',
        'dnstt_compat': False
    }
    
    if not out:
        return params
        
    for line in out.splitlines():
        line = line.strip()
        if line.startswith("ExecStart="):
            parts = line.split()
            for i, part in enumerate(parts):
                if part == '-domain' and i+1 < len(parts):
                    params['domain'] = parts[i+1]
                elif part == '-record-type' and i+1 < len(parts):
                    params['record_type'] = parts[i+1]
                elif part == '-idle-timeout' and i+1 < len(parts):
                    params['idle_timeout'] = parts[i+1]
                elif part == '-keepalive' and i+1 < len(parts):
                    params['keepalive'] = parts[i+1]
                elif part == '-udp' and i+1 < len(parts):
                    port_str = parts[i+1]
                    if port_str.startswith(':'):
                        params['vaydns_port'] = port_str[1:]
                    else:
                        params['vaydns_port'] = port_str
                elif part == '-upstream' and i+1 < len(parts):
                    upstream_str = parts[i+1]
                    if ':' in upstream_str:
                        params['upstream_port'] = upstream_str.split(':')[1]
                elif part == '-mtu' and i+1 < len(parts):
                    params['mtu'] = parts[i+1]
                elif part == '-dnstt-compat':
                    params['dnstt_compat'] = True
            break
            
    return params

def uninstall_vaydns(ssh, user, password, is_ubuntu, dante_service):
    """Handles complete removal of VayDNS services, binaries, and firewall rules."""
    print("===================================================================")
    print("                  UNINSTALLATION PROCESS")
    print("===================================================================")
    
    remove_user_input = input("Do you want to delete the 'vaydns' and proxy system users? (y/N) [default: no]: ").strip().lower()
    remove_user = remove_user_input in ['y', 'yes']

    print_step("Stopping and disabling VayDNS and Proxy services")
    run_cmd(ssh, "systemctl stop vaydns-server danted sockd shadowsocks || true", user, password)
    run_cmd(ssh, "systemctl disable vaydns-server danted sockd shadowsocks || true", user, password)

    print_step("Removing systemd service files, binaries, and configurations")
    run_cmd(ssh, "rm -f /etc/systemd/system/vaydns-server.service /etc/systemd/system/shadowsocks.service", user, password)
    run_cmd(ssh, "systemctl daemon-reload", user, password)
    run_cmd(ssh, "rm -rf /etc/vaydns /etc/shadowsocks", user, password)
    run_cmd(ssh, "rm -f /usr/local/bin/vaydns-server /usr/local/bin/ssserver", user, password)

    if remove_user:
        print_step("Deleting system users")
        run_cmd(ssh, "userdel vaydns || true", user, password)
        run_cmd(ssh, "userdel proxyuser || true", user, password)
        run_cmd(ssh, "userdel -r sshproxy || true", user, password)
        print("[+] Users removed.")
    else:
        print("[*] Preserving system users.")

    print_step("Removing DNS port forwarding firewall rules")
    if is_ubuntu:
        run_cmd(ssh, "iptables -t nat -D PREROUTING -p udp --dport 53 -j REDIRECT --to-ports 5300 || true", user, password)
        run_cmd(ssh, "netfilter-persistent save || true", user, password)
        print("[+] Removed iptables UDP 53 port redirection.")
    else:
        run_cmd(ssh, "firewall-cmd --permanent --remove-forward-port=port=53:proto=udp:toport=5300 || true", user, password)
        run_cmd(ssh, "firewall-cmd --reload || true", user, password)
        print("[+] Removed firewalld UDP 53 port redirection.")

    print("\n===================================================================")
    print("✅ VayDNS and all associated proxies have been successfully uninstalled!")
    print("ℹ️  Note: SSH configurations and ports were untouched.")
    print("===================================================================")

def update_vaydns(ssh, user, password, is_ubuntu, dante_service, dante_config_path):
    """Updates the systemd service, proxy matrix, and firewall rules with new parameters."""
    print("===================================================================")
    print("                  UPDATE CONFIGURATION PROCESS")
    print("===================================================================")
    
    print_step("Fetching current configuration from server")
    curr_params = get_current_vaydns_params(ssh, user, password)
    
    c_domain = curr_params['domain']
    domain_prompt = f"Tunnel domain name(s) (comma-separated) [{c_domain}]: " if c_domain else "Tunnel domain name(s) (comma-separated): "
    domain = input(domain_prompt).strip() or c_domain
    while not domain:
        domain = input("Domain is required: ").strip()
        
    c_dnstt = curr_params['dnstt_compat']
    dnstt_prompt_default = 'yes' if c_dnstt else 'no'
    dnstt_input = input(f"Enable DNSTT compatibility? (y/n) [default: {dnstt_prompt_default}]: ").strip().lower()
    dnstt_compat = True if dnstt_input in ['y', 'yes'] else (False if dnstt_input in ['n', 'no'] else c_dnstt)

    # Contextual defaults based on DNSTT
    if dnstt_compat:
        def_it = "2m"
        def_ka = "10s"
        def_rt = "txt"
        dnstt_flag = "-dnstt-compat "
        print("[*] DNSTT compatibility enabled: Auto-defaulting to 'txt' record type.")
    else:
        def_rt = curr_params['record_type']
        def_it = curr_params['idle_timeout']
        def_ka = curr_params['keepalive']
        dnstt_flag = ""

    # Extra parameters (Record Type, Timeouts, MTU)
    record_type = input(f"Record type (caa, null, txt, cname, a, aaaa, mx, ns, srv) [default: {def_rt}]: ").strip().lower() or def_rt
    idle_timeout = input(f"Idle timeout [default: {def_it}]: ").strip() or def_it
    keepalive = input(f"Keepalive [default: {def_ka}]: ").strip() or def_ka
    
    c_vp = curr_params['vaydns_port']
    vaydns_port = input(f"VayDNS port [default: {c_vp}]: ").strip() or c_vp
    
    c_up = curr_params['upstream_port']
    upstream_port = input(f"Upstream Proxy Port (e.g. 8000 for Dante, 8388 for SS) [default: {c_up}]: ").strip() or c_up
    
    c_mtu = curr_params['mtu']
    mtu_input = input(f"MTU (512-1452) [default: {c_mtu}]: ").strip() or c_mtu
    try:
        mtu_val = int(mtu_input)
        mtu = str(mtu_val) if 512 <= mtu_val <= 1452 else "1232"
    except ValueError:
        mtu = "1232"
    
    gen_keys = input("Generate new key pairs? (y/N) [default: no]: ").strip().lower() in ['y', 'yes']

    if gen_keys:
        print_step("Generating new cryptographic keys")
        cmd = "rm -f /etc/vaydns/server.key /etc/vaydns/server.pub && /usr/local/bin/vaydns-server -gen-key -privkey-file /etc/vaydns/server.key -pubkey-file /etc/vaydns/server.pub && chown -R vaydns:vaydns /etc/vaydns"
        run_cmd(ssh, cmd, user, password)
        print("[+] New keys generated successfully.")

    print_step("Checking if VayDNS service is currently running")
    _, check_active, _ = run_cmd(ssh, "systemctl is-active vaydns-server || true", user, password, hide_output=True)
    if check_active.strip() == "active":
        print("[*] VayDNS service is active. Stopping it before applying updates...")
        run_cmd(ssh, "systemctl stop vaydns-server", user, password)

    print_step("Updating Systemd Service Parameters")
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
ExecStart=/usr/local/bin/vaydns-server -udp :{vaydns_port} -privkey-file /etc/vaydns/server.key -mtu {mtu} -record-type {record_type} {dnstt_flag}-idle-timeout {idle_timeout} -keepalive {keepalive} -domain {domain} -upstream 127.0.0.1:{upstream_port}
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

    # Only update Dante if the upstream port points to Dante (8000)
    if upstream_port == "8000":
        print_step("Updating Dante Proxy Configuration")
        _, primary_interface, _ = run_cmd(ssh, "ip route | awk '/default/ {print $5}' | head -n1", user, password, hide_output=True)
        if not primary_interface:
            primary_interface = "eth0"
            
        sockd_content = f"""logoutput: stderr
internal: 127.0.0.1 port = {upstream_port}
external: {primary_interface}
socksmethod: none
clientmethod: none

client pass {{
        from: 127.0.0.1/32 to: 0.0.0.0/0
        log: connect error
}}

socks pass {{
        from: 127.0.0.1/32 to: 0.0.0.0/0
        protocol: tcp udp
        log: connect error
}}"""
        write_file_remote(ssh, dante_config_path, sockd_content, user, password)
        run_cmd(ssh, f"systemctl restart {dante_service}", user, password)

    print_step("Updating Firewall Routing Rules")
    if is_ubuntu:
        # Use insert (I) at line 1 to ensure the new port takes routing priority
        run_cmd(ssh, f"iptables -t nat -I PREROUTING 1 -p udp --dport 53 -j REDIRECT --to-ports {vaydns_port}", user, password)
        run_cmd(ssh, "netfilter-persistent save", user, password)
    else:
        # Remove standard port if present, then add the newly defined VayDNS port
        run_cmd(ssh, "firewall-cmd --permanent --remove-forward-port=port=53:proto=udp:toport=5300 || true", user, password)
        run_cmd(ssh, f"firewall-cmd --permanent --add-forward-port=port=53:proto=udp:toport={vaydns_port}", user, password)
        run_cmd(ssh, "firewall-cmd --reload", user, password)

    print_step("Restarting VayDNS Service")
    run_cmd(ssh, "systemctl daemon-reload", user, password)
    run_cmd(ssh, "systemctl start vaydns-server", user, password)

    print_step("Fetching Updated Keys and Generating Links")
    _, pubkey_raw, _ = run_cmd(ssh, "cat /etc/vaydns/server.pub", user, password, hide_output=True)
    pubkey = pubkey_raw.strip()

    print("\n✅ CONFIGURATION UPDATE SUCCESSFUL!")
    print("\n--- YOUR NEW VAYDNS ANDROID READY STRINGS ---")
    
    dnstt_url_flag = "true" if dnstt_compat else "false"
    domains_list = [d.strip() for d in domain.split(',') if d.strip()]
    for d in domains_list:
        client_config_url = f"dnst://{d}/vaydns/socks5?pubkey={pubkey}&record-type={record_type}&clientid-size=2&keepalive={keepalive}&idle-timeout={idle_timeout}&dnstt-compat={dnstt_url_flag}#vaydns"
        print(client_config_url)

def main():
    print("===================================================================")
    print("         VayDNS Server Cross-Platform Management Script")
    print("===================================================================")
    print("Select Action:")
    print("1. Install / Deploy VayDNS Server")
    print("2. Uninstall VayDNS Server")
    print("3. Update VayDNS Configuration")
    print("===================================================================")
    
    action_choice = input("Select an option (1/2/3) [default: 1]: ").strip()
    is_uninstall = action_choice == '2'
    is_update = action_choice == '3'

    if not is_uninstall and not is_update:
        print("\nREQUIREMENTS:")
        print("1. You MUST create an 'A' record and an 'NS' record in your domain registrar.")
        print("2. You need the root password or a user with sudo privileges.")
        print("===================================================================")
        ack = input("Have you set up your DNS records? Type 'y' to continue: ").strip().lower()
        if ack != 'y':
            print("Please configure your DNS records first. Exiting.")
            sys.exit(0)

    # Collect Server Connection Details
    print("\n--- Server Details ---")
    host = input("IPv4 address of the server: ").strip()
    
    current_ssh_port_input = input("SSH Port (default: 22): ").strip()
    current_ssh_port = int(current_ssh_port_input) if current_ssh_port_input.isdigit() else 22
    
    user = input("User (default: root): ").strip() or "root"
    password = getpass.getpass("Password: ")

    # Connect to Server via SSH
    print_step(f"Connecting to {host}:{current_ssh_port} as {user}")
    ssh = paramiko.SSHClient()
    ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    
    try:
        ssh.connect(hostname=host, port=current_ssh_port, username=user, password=password, timeout=10)
        print("[+] Successfully connected!")
    except Exception as e:
        print(f"[-] SSH Connection failed: {e}")
        sys.exit(1)

    # Detect Remote Operating System and Version
    print_step("Detecting remote operating system architecture and version")
    _, os_info, _ = run_cmd(ssh, "cat /etc/os-release", user, password, hide_output=True)
    os_info_lower = os_info.lower()
    
    is_ubuntu = "ubuntu" in os_info_lower or "debian" in os_info_lower

    version_id = ""
    for line in os_info.splitlines():
        if line.startswith("VERSION_ID="):
            version_id = line.split("=")[1].strip('"').strip("'")
            break
    major_ver = version_id.split('.')[0] if version_id else ""

    # Determine Shadowsocks version based on distribution and major version
    # RHEL 9 & Ubuntu 22 -> v1.17.1
    # RHEL 10 & Ubuntu 24/26 -> v1.25.0
    if is_ubuntu:
        if major_ver == "22" or version_id.startswith("22"):
            ss_version = "v1.17.1"
        else:
            ss_version = "v1.25.0"
        dante_config_path = "/etc/danted.conf"
        dante_service = "danted"
        print(f"[+] Detected Environment: Ubuntu/Debian (Version: {version_id or 'Unknown'}) -> Target SS: {ss_version}")
    else:
        if major_ver == "9" or version_id.startswith("9"):
            ss_version = "v1.17.1"
        else:
            ss_version = "v1.25.0"
        dante_config_path = "/etc/sockd.conf"
        dante_service = "sockd"
        print(f"[+] Detected Environment: RHEL/Rocky/Alma (Version: {version_id or 'Unknown'}) -> Target SS: {ss_version}")

    # =========================================================================
    # BRANCH TO UNINSTALL
    # =========================================================================
    if is_uninstall:
        uninstall_vaydns(ssh, user, password, is_ubuntu, dante_service)
        ssh.close()
        sys.exit(0)

    # =========================================================================
    # BRANCH TO UPDATE
    # =========================================================================
    if is_update:
        update_vaydns(ssh, user, password, is_ubuntu, dante_service, dante_config_path)
        ssh.close()
        sys.exit(0)

    # =========================================================================
    # INSTALLATION FLOW
    # =========================================================================
    change_ssh_port = input("\nDo you want to change the SSH port? (y/N) [default: no]: ").strip().lower()
    if change_ssh_port in ['y', 'yes']:
        new_ssh_port_input = input("Enter new SSH port [default: 2122]: ").strip()
        new_ssh_port = int(new_ssh_port_input) if new_ssh_port_input.isdigit() else 2122
    else:
        new_ssh_port = current_ssh_port

    domain = input("Tunnel domain name(s) (comma-separated for multiple, e.g., t1.example.com,t2.example.com): ").strip()
    
    # Evaluate DNSTT compatibility
    dnstt_input = input("Enable DNSTT compatibility? (y/N) [default: no]: ").strip().lower()
    dnstt_compat = dnstt_input in ['y', 'yes']

    if dnstt_compat:
        def_it = "2m"
        def_ka = "10s"
        def_rt = "txt"
        dnstt_flag = "-dnstt-compat "
        print("[*] DNSTT compatibility enabled: Auto-defaulting to 'txt' record type.")
    else:
        def_it = "10s"
        def_ka = "2s"
        def_rt = "caa"
        dnstt_flag = ""

    # Extra parameters (Record Type, Timeouts, MTU)
    record_type = input(f"Record type (caa, null, txt, cname, a, aaaa, mx, ns, srv) [default: {def_rt}]: ").strip().lower() or def_rt
    idle_timeout = input(f"Idle timeout [default: {def_it}]: ").strip() or def_it
    keepalive = input(f"Keepalive [default: {def_ka}]: ").strip() or def_ka

    mtu_input = input("MTU (512-1452) [default: 1232]: ").strip() or "1232"
    try:
        mtu_val = int(mtu_input)
        mtu = str(mtu_val) if 512 <= mtu_val <= 1452 else "1232"
    except ValueError:
        mtu = "1232"

    # =========================================================================
    # AUTHENTICATION / PROXY PROMPTS
    # =========================================================================
    print("\nSelect Proxy Method:")
    print("1. SOCKS5 (Default)")
    print("2. SSH")
    print("3. Shadowsocks")
    auth_choice = input("Enter choice (1/2/3) [default: 1]: ").strip()
    
    proxy_type = 'socks5'
    use_socks_auth = False
    upstream_port = 8000 # Default Dante port
    
    if auth_choice == '2':
        proxy_type = 'ssh'
        upstream_port = new_ssh_port # Tunnel points natively to SSH port
    elif auth_choice == '3':
        proxy_type = 'shadowsocks'
        upstream_port = 8388 # Default SS port
    else:
        ans_socks_auth = input("Enable SOCKS5 user/password authentication? (y/N) [default: no]: ").strip().lower()
        use_socks_auth = ans_socks_auth in ['y', 'yes']

    print("\n--- DNS Configuration ---")
    print("Note: Replacing the existing DNS servers with Google (8.8.8.8) and Cloudflare (1.1.1.1) may improve latency.")
    replace_dns = input("Do you want to replace the existing DNS servers? (Y/n) [default: yes]: ").strip().lower()
    replace_dns_bool = replace_dns in ['', 'y', 'yes']

    print("\n--- SSH Key Setup ---")
    setup_ssh_input = input("Do you want to set up passwordless SSH key? (y/N) [default: no]: ").strip().lower()
    pub_key_content = None

    if setup_ssh_input in ['y', 'yes']:
        pub_key_path = input("Enter the path to your public SSH key [Press Enter to generate a new one]: ").strip()
        
        if not pub_key_path or not os.path.exists(pub_key_path):
            print("No valid key provided. Generating a new RSA key pair locally...")
            default_key_path = os.path.expanduser("~/.ssh/id_rsa_vaydns")
            if not os.path.exists(default_key_path):
                subprocess.run(['ssh-keygen', '-t', 'rsa', '-b', '4096', '-f', default_key_path, '-N', ''], check=True)
            pub_key_path = f"{default_key_path}.pub"
            
        with open(pub_key_path, "r") as f:
            pub_key_content = f.read().strip()
        print(f"[+] Using public key: {pub_key_path}")
    else:
        print("[*] Skipping passwordless SSH key setup.")

    # 0. Configure DNS Servers
    if replace_dns_bool:
        print_step("Updating DNS servers (commenting out old entries)")
        run_cmd(ssh, "chattr -i /etc/resolv.conf || true", user, password, hide_output=True)
        _, resolv_current, _ = run_cmd(ssh, "cat /etc/resolv.conf", user, password, hide_output=True)
        
        new_lines = []
        for line in resolv_current.splitlines():
            line_stripped = line.strip()
            if line_stripped.startswith("nameserver"):
                if "8.8.8.8" in line_stripped or "1.1.1.1" in line_stripped:
                    continue
                new_lines.append("# " + line_stripped)
            else:
                new_lines.append(line_stripped)
                
        new_lines.append("nameserver 8.8.8.8")
        new_lines.append("nameserver 1.1.1.1")
        
        resolv_content = "\n".join(new_lines)
        write_file_remote(ssh, "/etc/resolv.conf", resolv_content, user, password)
        run_cmd(ssh, "chattr +i /etc/resolv.conf", user, password, hide_output=True)
        run_cmd(ssh, "nmcli general reload || true", user, password, hide_output=True)
        print("[+] DNS servers successfully updated and locked.")

    # 1. System Updates & Packages
    print_step("Updating system package repositories and installing core dependencies")
    print("[*] Note: Updating system packages may take up to 10 minutes depending on server updates.")
    if is_ubuntu: 
        run_cmd(ssh, "export DEBIAN_FRONTEND=noninteractive && apt-get update -y", user, password)
        run_cmd(ssh, "export DEBIAN_FRONTEND=noninteractive && apt-get install tar dante-server iptables iptables-persistent curl vnstat sed tcpdump net-tools bind9-dnsutils policycoreutils wget xz-utils -y", user, password)
    else:
        run_cmd(ssh, "dnf update -y", user, password)
        run_cmd(ssh, "dnf install epel-release -y", user, password)
        run_cmd(ssh, "dnf install tar dante-server firewalld policycoreutils-python-utils curl tcpdump net-tools bind-utils vnstat sed wget xz -y", user, password)

    # 2. Firewall Configuration
    print_step("Configuring target platform firewall policies")
    if is_ubuntu:
        run_cmd(ssh, "systemctl stop ufw || true", user, password)
        run_cmd(ssh, "systemctl disable ufw || true", user, password)
        
        run_cmd(ssh, "iptables -F", user, password)
        run_cmd(ssh, "iptables -X", user, password)
        run_cmd(ssh, "iptables -t nat -F", user, password)
        
        # 1. ALLOW REQUIRED TRAFFIC FIRST
        run_cmd(ssh, "iptables -A INPUT -i lo -j ACCEPT", user, password)
        run_cmd(ssh, "iptables -A INPUT -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT", user, password)
        run_cmd(ssh, f"iptables -A INPUT -p tcp --dport {current_ssh_port} -j ACCEPT", user, password)
        
        if current_ssh_port != new_ssh_port:
            run_cmd(ssh, f"iptables -A INPUT -p tcp --dport {new_ssh_port} -j ACCEPT", user, password)
            
        run_cmd(ssh, "iptables -t nat -A PREROUTING -p udp --dport 53 -j REDIRECT --to-ports 5300", user, password)
        
        # 2. NOW SAFE TO DROP EVERYTHING ELSE
        run_cmd(ssh, "iptables -P INPUT DROP", user, password)
        run_cmd(ssh, "iptables -P FORWARD ACCEPT", user, password)
        run_cmd(ssh, "iptables -P OUTPUT ACCEPT", user, password)
        
        run_cmd(ssh, "mkdir -p /etc/iptables", user, password)
        run_cmd(ssh, "iptables-save > /etc/iptables/rules.v4", user, password)
        run_cmd(ssh, "netfilter-persistent save", user, password)
        print("[+] iptables successfully configured and saved.")
        
    else:
        run_cmd(ssh, "systemctl start firewalld", user, password)
        run_cmd(ssh, "systemctl enable firewalld", user, password)
        
        run_cmd(ssh, f"firewall-cmd --permanent --add-port={new_ssh_port}/tcp", user, password)
        run_cmd(ssh, "firewall-cmd --permanent --add-forward-port=port=53:proto=udp:toport=5300", user, password)
        run_cmd(ssh, "firewall-cmd --permanent --zone=public --set-target=DROP", user, password)
        run_cmd(ssh, "firewall-cmd --permanent --add-masquerade", user, password)
        run_cmd(ssh, "firewall-cmd --reload", user, password)

    # 3. Create Service User
    print_step("Validating and creating system service accounts")
    run_cmd(ssh, "id -u vaydns &>/dev/null || useradd -r -M -s /bin/false -c 'vaydns service user' -d /nonexistent vaydns", user, password)

    # Configure Passwordless SSH
    if pub_key_content:
        print_step("Configuring Passwordless SSH Login")
        run_cmd(ssh, "mkdir -p ~/.ssh && chmod 700 ~/.ssh", user, password, hide_output=True)
        run_cmd(ssh, f"echo '{pub_key_content}' >> ~/.ssh/authorized_keys", user, password, hide_output=True)
        run_cmd(ssh, "chmod 600 ~/.ssh/authorized_keys", user, password, hide_output=True)
        print("[+] Public key successfully added to authorized_keys.")

    # 4. Download Binary & Generate Keys
    print_step("Downloading deployment binary and generating secure cryptographic keys")
    binary_url = "https://raw.githubusercontent.com/phoenixdnsvpn/phoenix-vpn/main/scripts/vaydns-server"
    
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

    if not is_ubuntu:
        print_step("Applying target SELinux security context rules")
        run_cmd(ssh, 'semanage fcontext -a -t bin_t "/usr/local/bin/vaydns-server"', user, password)
        run_cmd(ssh, "restorecon -v /usr/local/bin/vaydns-server", user, password)

    # =====================================================================
    # AUTHENTICATION PROTOCOL SETUP
    # =====================================================================
    generated_password = ""
    ssh_pub_key = ""
    ssh_priv_key = ""

    if proxy_type == 'socks5':
        dante_socksmethod = "none"
        if use_socks_auth:
            print_step("Configuring SOCKS5 User Authentication")
            generated_password = generate_password()
            run_cmd(ssh, "useradd -r -s /usr/sbin/nologin proxyuser || true", user, password)
            run_cmd(ssh, f"echo 'proxyuser:{generated_password}' | chpasswd", user, password)
            dante_socksmethod = "username"

        print_step("Detecting primary external network interface")
        _, primary_interface, _ = run_cmd(ssh, "ip route | awk '/default/ {print $5}' | head -n1", user, password, hide_output=True)
        if not primary_interface:
            primary_interface = "eth0"
            
        print_step(f"Deploying Dante proxy configuration to {dante_config_path}")
        run_cmd(ssh, f"mv {dante_config_path} {dante_config_path}.1 || true", user, password)
        
        sockd_content = f"""logoutput: stderr
internal: 127.0.0.1 port = 8000
external: {primary_interface}
socksmethod: {dante_socksmethod}
clientmethod: none

client pass {{
        from: 127.0.0.1/32 to: 0.0.0.0/0
        log: connect error
}}

socks pass {{
        from: 127.0.0.1/32 to: 0.0.0.0/0
        protocol: tcp udp
        log: connect error
}}"""
        write_file_remote(ssh, dante_config_path, sockd_content, user, password)
        run_cmd(ssh, "systemctl daemon-reload", user, password)
        run_cmd(ssh, f"systemctl start {dante_service}", user, password)
        run_cmd(ssh, f"systemctl enable {dante_service}", user, password)

    elif proxy_type == 'ssh':
        print_step("Configuring SSH Proxy Authentication")
        run_cmd(ssh, "useradd -m sshproxy || true", user, password)
        
        # CLEAR old keys to prevent the interactive `ssh-keygen` overwrite prompt hang
        run_cmd(ssh, 'mkdir -p /home/sshproxy/.ssh && rm -f /home/sshproxy/.ssh/id_ed25519* && ssh-keygen -t ed25519 -f /home/sshproxy/.ssh/id_ed25519 -N "" -q', user, password)
        
        run_cmd(ssh, "cp /home/sshproxy/.ssh/id_ed25519.pub /home/sshproxy/.ssh/authorized_keys", user, password)
        run_cmd(ssh, "chown -R sshproxy:sshproxy /home/sshproxy/.ssh && chmod 700 /home/sshproxy/.ssh && chmod 600 /home/sshproxy/.ssh/authorized_keys", user, password)
        
        _, ssh_pub_key, _ = run_cmd(ssh, "cat /home/sshproxy/.ssh/id_ed25519.pub", user, password, hide_output=True)
        _, ssh_priv_key, _ = run_cmd(ssh, "cat /home/sshproxy/.ssh/id_ed25519", user, password, hide_output=True)

    elif proxy_type == 'shadowsocks':
        print_step(f"Installing and Configuring Shadowsocks-Rust ({ss_version})")
        generated_password = generate_password()
        ss_install_cmd = f"""
        cd /tmp
        wget -q "https://github.com/shadowsocks/shadowsocks-rust/releases/download/{ss_version}/shadowsocks-{ss_version}.x86_64-unknown-linux-gnu.tar.xz" -O ss.tar.xz
        tar -xf ss.tar.xz
        mv ssserver /usr/local/bin/
        chmod 755 /usr/local/bin/ssserver
        rm -f ss.tar.xz sslocal ssmanager ssurl
        mkdir -p /etc/shadowsocks
        """
        run_cmd(ssh, ss_install_cmd, user, password)

        if not is_ubuntu:
            print_step("Applying target SELinux security context rules for Shadowsocks")
            run_cmd(ssh, "semanage fcontext -a -t bin_t '/usr/local/bin/ssserver'", user, password)
            run_cmd(ssh, "restorecon -v /usr/local/bin/ssserver", user, password)

        ss_config = f"""{{
    "server":"127.0.0.1",
    "server_port":8388,
    "local_port":1080,
    "password":"{generated_password}",
    "timeout":300,
    "method":"chacha20-ietf-poly1305"
}}"""
        write_file_remote(ssh, "/etc/shadowsocks/config.json", ss_config, user, password)

        ss_service = """[Unit]
Description=Shadowsocks-Rust Server
After=network.target

[Service]
Type=simple
User=root
ExecStart=/usr/local/bin/ssserver -c /etc/shadowsocks/config.json
Restart=always
RestartSec=3

[Install]
WantedBy=multi-user.target"""
        write_file_remote(ssh, "/etc/systemd/system/shadowsocks.service", ss_service, user, password)
        run_cmd(ssh, "systemctl daemon-reload && systemctl enable --now shadowsocks", user, password)

    # 5. Create SystemD Service
    print_step("Checking for existing VayDNS service")
    _, check_active, _ = run_cmd(ssh, "systemctl is-active vaydns-server || true", user, password, hide_output=True)
    if check_active.strip() == "active":
        print("[*] VayDNS service is currently running. Stopping it before applying updates...")
        run_cmd(ssh, "systemctl stop vaydns-server", user, password)

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
ExecStart=/usr/local/bin/vaydns-server -udp :5300 -privkey-file /etc/vaydns/server.key -mtu {mtu} -record-type {record_type} {dnstt_flag}-idle-timeout {idle_timeout} -keepalive {keepalive} -domain {domain} -upstream 127.0.0.1:{upstream_port}
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
    
    print_step("Booting up and enabling VayDNS core tunnel infrastructure")
    run_cmd(ssh, "systemctl daemon-reload", user, password)
    run_cmd(ssh, "systemctl start vaydns-server", user, password)
    run_cmd(ssh, "systemctl enable vaydns-server", user, password)

    print_step("Booting up and enabling vnstat for traffic monitoring")
    run_cmd(ssh, "systemctl start vnstat", user, password)
    run_cmd(ssh, "systemctl enable vnstat", user, password)

    # 8. Modify SSH Daemon Port (Safe Update & SELinux Config)
    if current_ssh_port != new_ssh_port:
        print_step(f"Changing SSH port from {current_ssh_port} to {new_ssh_port}")
        config_path = "/etc/ssh/sshd_config"
        
        if not is_ubuntu:
            _, selinux_check, _ = run_cmd(ssh, "getenforce", user, password, hide_output=True)
            if "Disabled" not in selinux_check:
                print("[*] SELinux is active. Updating policy for new SSH port...")
                run_cmd(ssh, "which semanage || dnf install -y policycoreutils-python-utils", user, password)
                run_cmd(ssh, f"semanage port -a -t ssh_port_t -p tcp {new_ssh_port} || semanage port -m -t ssh_port_t -p tcp {new_ssh_port}", user, password)
                
        # Update sshd_config with the new port
        sed_cmd = f"sed -i 's/^#\\?Port .*/Port {new_ssh_port}/' {config_path} && if ! grep -q '^Port {new_ssh_port}' {config_path}; then echo 'Port {new_ssh_port}' >> {config_path}; fi"
        run_cmd(ssh, sed_cmd, user, password, hide_output=True)
        
        # Validate SSH configuration syntax before attempting a restart or removing firewall rules
        exit_code, _, err_out = run_cmd(ssh, "sshd -t", user, password, hide_output=True)
        if exit_code != 0:
            print(f"[!] Warning: SSH configuration test failed: {err_out}. Reverting SSH port back to {current_ssh_port}.")
            run_cmd(ssh, f"sed -i 's/^#\\?Port .*/Port {current_ssh_port}/' {config_path}", user, password, hide_output=True)
        else:
            # Restart SSH Daemon based on distro
            if is_ubuntu:
                run_cmd(ssh, "systemctl daemon-reload && (systemctl restart ssh.socket || systemctl restart ssh)", user, password)
            else:
                run_cmd(ssh, "systemctl restart sshd", user, password)

            # Cleanup old firewall rule only AFTER new configuration succeeds
            if is_ubuntu:
                run_cmd(ssh, f"iptables -D INPUT -p tcp --dport {current_ssh_port} -j ACCEPT || true", user, password)
                run_cmd(ssh, "netfilter-persistent save", user, password)
            else:
                if current_ssh_port == 22:
                    run_cmd(ssh, "firewall-cmd --permanent --remove-service=ssh || true", user, password)
                else:
                    run_cmd(ssh, f"firewall-cmd --permanent --remove-port={current_ssh_port}/tcp || true", user, password)
                run_cmd(ssh, "firewall-cmd --reload", user, password)
            
            print(f"[+] SSH daemon successfully updated to listen on port {new_ssh_port}.")
    
    # 9. Retrieve Data
    print_step("Fetching generation keys and diagnostics reports from deployment")
    _, pubkey_raw, _ = run_cmd(ssh, "cat /etc/vaydns/server.pub", user, password, hide_output=True)
    pubkey = pubkey_raw.strip()
    
    _, status_out, _ = run_cmd(ssh, "systemctl status vaydns-server --no-pager", user, password, hide_output=True)
    
    ssh.close()

    # 10. Output Results to User
    print("\n===================================================================")
    print("                    SERVER STATUS REPORT")
    print("===================================================================")
    print(status_out)
    print("===================================================================")
    
    if not pubkey:
        print("\n[!] Error: Could not retrieve the public key. Check the server logs.")
        sys.exit(1)

    print("\n✅ DEPLOYMENT AND CONFIGURATION LINK GENERATION SUCCESSFUL!")
    
    print("\n--- SERVER ACCESS INSTRUCTIONS ---")
    if new_ssh_port != 22:
        print(f"• To access the server using a password, type: ssh -p {new_ssh_port} {user}@{host}")
    else:
        print(f"• To access the server using a password, type: ssh {user}@{host}")
    
    if pub_key_content:
        print(f"• Since we are using SSH keys, to login to the server without a password, simply type: ssh root@{host}" + (f" -p {new_ssh_port}" if new_ssh_port != 22 else ""))

    if proxy_type == 'socks5' and use_socks_auth:
        print("\n--- SOCKS5 CREDENTIALS ---")
        print(f"Username: proxyuser")
        print(f"Password: {generated_password}")
    elif proxy_type == 'ssh':
        print("\n--- SSH PROXY CREDENTIALS ---")
        print(f"Username: sshproxy")
        print("\n[Client Public Key - Optional]:\n" + ssh_pub_key.strip())
        print("\n[Client Private Key - REQUIRED FOR CONNECTION]:\n" + ssh_priv_key.strip())
    elif proxy_type == 'shadowsocks':
        print("\n--- SHADOWSOCKS CREDENTIALS ---")
        print(f"Password: {generated_password}")
        print(f"Method: chacha20-ietf-poly1305")

    print("\n--- YOUR VAYDNS ANDROID READY STRINGS ---")
    dnstt_url_flag = "true" if dnstt_compat else "false"
    backend_slug = proxy_type if proxy_type != 'shadowsocks' else 'ss'

    domains_list = [d.strip() for d in domain.split(',') if d.strip()]
    for d in domains_list:
        client_config_url = f"dnst://{d}/vaydns/{backend_slug}?pubkey={pubkey}&record-type={record_type}&clientid-size=2&keepalive={keepalive}&idle-timeout={idle_timeout}&dnstt-compat={dnstt_url_flag}#vaydns"
        print(client_config_url)

    print("\nImport Method:")
    print("1. Launch VayDNS Android.")
    print("2. Open context operations menu (top-right dashboard).")
    print("3. Choose 'Import' and commit these string layouts onto your configuration profile engine.")

if __name__ == "__main__":
    main()

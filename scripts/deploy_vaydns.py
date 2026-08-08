import os
import subprocess
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

def get_current_vaydns_params(ssh, user, password):
    """Fetches and parses the current vaydns-server.service parameters."""
    _, out, _ = run_cmd(ssh, "cat /etc/systemd/system/vaydns-server.service || true", user, password, hide_output=True)
    
    params = {
        'domain': '',
        'record_type': 'caa',
        'idle_timeout': '10s',
        'keepalive': '2s',
        'vaydns_port': '5300',
        'dante_port': '8000',
        'mtu': '1232'
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
                        params['dante_port'] = upstream_str.split(':')[1]
                elif part == '-mtu' and i+1 < len(parts):
                    params['mtu'] = parts[i+1]
            break
            
    return params

def uninstall_vaydns(ssh, user, password, is_ubuntu, dante_service):
    """Handles complete removal of VayDNS services, binaries, and firewall rules."""
    print("===================================================================")
    print("                  UNINSTALLATION PROCESS")
    print("===================================================================")
    
    # Prompt for user deletion preference locally before running commands
    remove_user_input = input("Do you want to delete the 'vaydns' system user? (y/N) [default: no]: ").strip().lower()
    remove_user = remove_user_input in ['y', 'yes']

    # 1. Stop and disable services
    print_step("Stopping and disabling VayDNS and Dante services")
    run_cmd(ssh, "systemctl stop vaydns-server || true", user, password)
    run_cmd(ssh, "systemctl disable vaydns-server || true", user, password)
    run_cmd(ssh, f"systemctl stop {dante_service} || true", user, password)
    run_cmd(ssh, f"systemctl disable {dante_service} || true", user, password)

    # 2. Remove systemd service file, binaries, and configuration directories
    print_step("Removing VayDNS systemd service, binaries, and configurations")
    run_cmd(ssh, "rm -f /etc/systemd/system/vaydns-server.service", user, password)
    run_cmd(ssh, "systemctl daemon-reload", user, password)
    run_cmd(ssh, "rm -rf /etc/vaydns", user, password)
    run_cmd(ssh, "rm -f /usr/local/bin/vaydns-server", user, password)

    # 3. Optional: Delete system user
    if remove_user:
        print_step("Deleting system user 'vaydns'")
        run_cmd(ssh, "userdel vaydns || true", user, password)
        print("[+] User 'vaydns' removed.")
    else:
        print("[*] Preserving system user 'vaydns'.")

    # 4. Remove port forwarding rules in firewall (Do NOT touch SSH)
    print_step("Removing DNS port forwarding firewall rules")
    if is_ubuntu:
        # Remove iptables NAT rule
        run_cmd(ssh, "iptables -t nat -D PREROUTING -p udp --dport 53 -j REDIRECT --to-ports 5300 || true", user, password)
        run_cmd(ssh, "netfilter-persistent save || true", user, password)
        print("[+] Removed iptables UDP 53 port redirection.")
    else:
        # Remove firewalld forward port
        run_cmd(ssh, "firewall-cmd --permanent --remove-forward-port=port=53:proto=udp:toport=5300 || true", user, password)
        run_cmd(ssh, "firewall-cmd --reload || true", user, password)
        print("[+] Removed firewalld UDP 53 port redirection.")

    print("\n===================================================================")
    print("✅ VayDNS and Dante proxy have been successfully uninstalled!")
    print("ℹ️  Note: SSH configurations and ports were untouched.")
    print("===================================================================")

def update_vaydns(ssh, user, password, is_ubuntu, dante_service, dante_config_path):
    """Updates the systemd service, proxy matrix, and firewall rules with new parameters."""
    print("===================================================================")
    print("                  UPDATE CONFIGURATION PROCESS")
    print("===================================================================")
    
    print_step("Fetching current configuration from server")
    curr_params = get_current_vaydns_params(ssh, user, password)
    
    # Domain
    c_domain = curr_params['domain']
    domain_prompt = f"Tunnel domain name(s) (comma-separated) [{c_domain}]: " if c_domain else "Tunnel domain name(s) (comma-separated, e.g., t1.com,t2.com): "
    domain = input(domain_prompt).strip() or c_domain
    while not domain:
        domain = input("Domain is required: ").strip()
        
    # Other parameters
    c_rt = curr_params['record_type']
    record_type = input(f"Record type (caa, null, txt) [default: {c_rt}]: ").strip().lower() or c_rt
    
    c_it = curr_params['idle_timeout']
    idle_timeout = input(f"Idle timeout [default: {c_it}]: ").strip() or c_it
    
    c_ka = curr_params['keepalive']
    keepalive = input(f"Keepalive [default: {c_ka}]: ").strip() or c_ka
    
    c_vp = curr_params['vaydns_port']
    vaydns_port = input(f"VayDNS port [default: {c_vp}]: ").strip() or c_vp
    
    c_dp = curr_params['dante_port']
    dante_port = input(f"Dante upstream port [default: {c_dp}]: ").strip() or c_dp
    
    c_mtu = curr_params['mtu']
    mtu = input(f"MTU [default: {c_mtu}]: ").strip() or c_mtu
    
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
ExecStart=/usr/local/bin/vaydns-server -udp :{vaydns_port} -privkey-file /etc/vaydns/server.key -mtu {mtu} -record-type {record_type} -idle-timeout {idle_timeout} -keepalive {keepalive} -domain {domain} -upstream 127.0.0.1:{dante_port}
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

    print_step("Updating Dante Proxy Configuration")
    _, primary_interface, _ = run_cmd(ssh, "ip route | awk '/default/ {print $5}' | head -n1", user, password, hide_output=True)
    if not primary_interface:
        primary_interface = "eth0"
        
    sockd_content = f"""logoutput: stderr
internal: 127.0.0.1 port = {dante_port}
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

    print_step("Restarting Services")
    run_cmd(ssh, "systemctl daemon-reload", user, password)
    run_cmd(ssh, f"systemctl restart {dante_service}", user, password)
    run_cmd(ssh, "systemctl start vaydns-server", user, password)

    print_step("Fetching Updated Keys and Generating Links")
    _, pubkey_raw, _ = run_cmd(ssh, "cat /etc/vaydns/server.pub", user, password, hide_output=True)
    pubkey = pubkey_raw.strip()

    print("\n✅ CONFIGURATION UPDATE SUCCESSFUL!")
    print("\n--- YOUR NEW VAYDNS ANDROID READY STRINGS ---")
    
    domains_list = [d.strip() for d in domain.split(',') if d.strip()]
    for d in domains_list:
        client_config_url = f"dnst://{d}/vaydns/socks5?pubkey={pubkey}&record-type={record_type}&clientid-size=2&keepalive={keepalive}&idle-timeout={idle_timeout}#vaydns"
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
    if change_ssh_port == 'y':
        new_ssh_port_input = input("Enter new SSH port [default: 2222]: ").strip()
        new_ssh_port = int(new_ssh_port_input) if new_ssh_port_input.isdigit() else 2222
    else:
        new_ssh_port = current_ssh_port

    domain = input("Tunnel domain name(s) (comma-separated for multiple, e.g., t1.example.com,t2.example.com): ").strip()
    record_type = input("Record type (caa, null, txt) [default: caa]: ").strip().lower() or "caa"

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
        run_cmd(ssh, "export DEBIAN_FRONTEND=noninteractive && apt-get install tar dante-server iptables iptables-persistent curl vnstat sed tcpdump net-tools bind9-dnsutils policycoreutils -y", user, password)
    else:
        run_cmd(ssh, "dnf update -y", user, password)
        run_cmd(ssh, "dnf install epel-release -y", user, password)
        run_cmd(ssh, "dnf install tar dante-server firewalld policycoreutils-python-utils curl tcpdump net-tools bind-utils vnstat sed -y", user, password)

    # 2. Firewall Configuration
    print_step("Configuring target platform firewall policies")
    if is_ubuntu:
        run_cmd(ssh, "systemctl stop ufw || true", user, password)
        run_cmd(ssh, "systemctl disable ufw || true", user, password)
        
        run_cmd(ssh, "iptables -F", user, password)
        run_cmd(ssh, "iptables -X", user, password)
        run_cmd(ssh, "iptables -t nat -F", user, password)
        
        run_cmd(ssh, "iptables -P INPUT DROP", user, password)
        run_cmd(ssh, "iptables -P FORWARD ACCEPT", user, password)
        run_cmd(ssh, "iptables -P OUTPUT ACCEPT", user, password)
        
        run_cmd(ssh, "iptables -A INPUT -i lo -j ACCEPT", user, password)
        run_cmd(ssh, "iptables -A INPUT -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT", user, password)
        run_cmd(ssh, f"iptables -A INPUT -p tcp --dport {current_ssh_port} -j ACCEPT", user, password)
        
        if current_ssh_port != new_ssh_port:
            run_cmd(ssh, f"iptables -A INPUT -p tcp --dport {new_ssh_port} -j ACCEPT", user, password)
            
        run_cmd(ssh, "iptables -t nat -A PREROUTING -p udp --dport 53 -j REDIRECT --to-ports 5300", user, password)
        
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

    # Configure Passwordless SSH (Moved after firewall/user creation to ensure directory structures)
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

    # 6. Configure Dante Proxy Matrix with Dynamic Interface Detection
    print_step("Detecting primary external network interface")
    _, primary_interface, _ = run_cmd(ssh, "ip route | awk '/default/ {print $5}' | head -n1", user, password, hide_output=True)
    if not primary_interface:
        primary_interface = "eth0"
        
    print(f"[+] Detected external interface: {primary_interface}")
    print_step(f"Deploying custom Dante proxy configurations to {dante_config_path}")
    
    run_cmd(ssh, f"mv {dante_config_path} {dante_config_path}.1 || true", user, password)
    
    sockd_content = f"""logoutput: stderr
internal: 127.0.0.1 port = 8000
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

    # 7. Start Services
    print_step(f"Booting up and enabling backend Dante proxy ({dante_service})")
    run_cmd(ssh, "systemctl daemon-reload", user, password)
    run_cmd(ssh, f"systemctl start {dante_service}", user, password)
    run_cmd(ssh, f"systemctl enable {dante_service}", user, password)
    
    print_step("Booting up and enabling VayDNS core tunnel core infrastructure")
    run_cmd(ssh, "systemctl start vaydns-server", user, password)
    run_cmd(ssh, "systemctl enable vaydns-server", user, password)

    print_step("Booting up and enabling vnstat for traffic monitoring")
    run_cmd(ssh, "systemctl start vnstat", user, password)
    run_cmd(ssh, "systemctl enable vnstat", user, password)

    # 8. Modify SSH Daemon Port
    if current_ssh_port != new_ssh_port:
        print_step(f"Changing SSH port from {current_ssh_port} to {new_ssh_port}")
        config_path = "/etc/ssh/sshd_config"
        
        if not is_ubuntu:
            _, selinux_check, _ = run_cmd(ssh, "getenforce", user, password, hide_output=True)
            if "Disabled" not in selinux_check:
                print("[*] SELinux is active. Updating policy for new SSH port...")
                run_cmd(ssh, f"semanage port -a -t ssh_port_t -p tcp {new_ssh_port} || true", user, password)
                
        if is_ubuntu:
            run_cmd(ssh, f"iptables -D INPUT -p tcp --dport {current_ssh_port} -j ACCEPT || true", user, password)
            run_cmd(ssh, "netfilter-persistent save", user, password)
        else:
            if current_ssh_port == 22:
                run_cmd(ssh, "firewall-cmd --permanent --remove-service=ssh", user, password)
            else:
                run_cmd(ssh, f"firewall-cmd --permanent --remove-port={current_ssh_port}/tcp", user, password)
            run_cmd(ssh, "firewall-cmd --reload", user, password)
            
        sed_cmd = f"sed -i 's/^#\\?Port .*/Port {new_ssh_port}/' {config_path} && if ! grep -q '^Port {new_ssh_port}' {config_path}; then echo 'Port {new_ssh_port}' >> {config_path}; fi"
        run_cmd(ssh, sed_cmd, user, password, hide_output=True)
        
        if is_ubuntu:
            run_cmd(ssh, "systemctl restart ssh", user, password)
        else:
            run_cmd(ssh, "systemctl restart sshd", user, password)
            
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

    print("\n--- YOUR VAYDNS ANDROID READY STRINGS ---")
    domains_list = [d.strip() for d in domain.split(',') if d.strip()]
    for d in domains_list:
        client_config_url = f"dnst://{d}/vaydns/socks5?pubkey={pubkey}&record-type={record_type}&clientid-size=2&keepalive=2s&idle-timeout=10s#vaydns"
        print(client_config_url)

    print("\nImport Method:")
    print("1. Launch VayDNS Android.")
    print("2. Open context operations menu (top-right dashboard).")
    print("3. Choose 'Import' and commit these string layouts onto your configuration profile engine.")

if __name__ == "__main__":
    main()

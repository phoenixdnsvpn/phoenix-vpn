
---

# 🚀 VayDNS Server Automated Setup

A cross-platform Python automation script designed to instantly deploy the VayDNS tunnel server and Dante SOCKS proxy infrastructure. This script automatically detects your operating system, configures the necessary firewalls (UFW or Firewalld), generates cryptographic keys, and outputs a ready-to-use Android client configuration string.

**Supported Operating Systems:**

* **RHEL-based:** Rocky Linux 9/10, AlmaLinux 9/10, CentOS Stream
* **Debian-based:** Ubuntu 22.04, Ubuntu 24.04, Debian 11/12

---

## 📋 Prerequisites & Preparation

Before running this script, you must have the following prepared. **Do not run the script until your DNS records are fully propagated.**

1. **A Registered Domain Name (QNAME Constraints):** You must own a domain name and have access to its DNS management panel. *(If you do not already own one, you can register a cheap, short domain from providers like [Namecheap](https://www.namecheap.com)).*
* ⚠️ **CRITICAL TUNNEL CONSTRAINT:** Your domain name must be **as short as physically possible** (e.g., `example.com`). Furthermore, the tunnel subdomain must be exactly **one character** (e.g., `t.example.com`).
* *Why?* VayDNS encodes your internet traffic inside the DNS query name (QNAME). Heavily censored networks often block DNS requests if the query string is too long (typically > 110 characters). A long domain name like `my-personal-private-tunnel.example.com` will consume all the available character space, leaving no room for the actual encrypted data payload, which will cause the VPN connection to fail or throw MTU size errors.
2. **DNS Records Configuration:** You must create two specific records pointing to your VPS:
* **`A` Record:** Point a subdomain (e.g., `ns1.example.com`) to your server's IPv4 address.
* **`NS` Record:** Point your desired tunnel domain (e.g., `t.example.com`) to the `A` record you just created (`ns1.example.com`).


3. **Server Credentials:** You need the IPv4 address of your server and the password for the `root` user (or a user with `sudo` privileges).
4. **Python Environment:** The machine running this script (your local computer) must have Python installed along with the `paramiko` library.

---

## ⚠️ Critical: Cloud Edge Firewalls

The setup script automatically configures the internal Linux OS firewalls (`firewalld` or `ufw`). However, it **cannot** control external cloud firewalls.

If your server is hosted on a provider with an external web-dashboard firewall (such as **OVH Network Security, AWS Security Groups, Oracle Cloud, or DigitalOcean Cloud Firewalls**), you **MUST manually allow Inbound UDP Port 53**.

If you fail to allow Inbound UDP 53 on your cloud dashboard, the network will silently destroy the VPN packets before they ever reach the Linux operating system.

---

## 🛠️ Installation & Usage

**1. Install the required Python library on your local machine:**

```bash
pip install paramiko

```

**2. Run the deployment script:**

```bash
python setup_vaydns.py

```

**3. Follow the interactive prompts.** The script will ask for your server IP, credentials, tunnel domain, and record type.

Once finished, the script will output a `dnst://` configuration string. Open your VayDNS Android app, tap the Menu in the top right, select **Import**, and paste the string to connect!

---

## 🔍 Troubleshooting: Connection Drops

If the installation succeeds but your VayDNS Android app hangs or fails to connect, the issue is almost always network-routing related (packets are being dropped).

You can easily diagnose where the packets are dying using `tcpdump`.

**1. SSH into your remote server.**
**2. Run the following command to listen to the raw network interfaces:**

```bash
tcpdump -n -i any udp port 53 or udp port 5300

```

**3. While the command is running, try to connect using your VayDNS Android app.**

### How to read the results:

* **Silent Output (Nothing appears):** The packets are being destroyed in the cloud. Check your hosting provider's external Edge Firewall / Security Group and ensure Ingress UDP Port 53 is allowed.
* **Traffic on Port 53, but NOT Port 5300:** Your cloud firewall is allowing the traffic, but the internal Linux firewall (UFW/Firewalld) is failing to forward Port 53 to Port 5300. Rerun the firewall configuration steps.
* **Traffic on Port 5300:** The network is perfect. The packets are reaching the VayDNS application. Check the `vaydns-server` systemd logs (`journalctl -u vaydns-server -f`) for cryptographic or DNS mismatch errors.

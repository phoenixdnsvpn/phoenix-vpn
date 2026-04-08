# F35

F35 is an end-to-end DNS resolver scanner for real tunnel testing.

It does not only ask a DNS question and call that a success.
It actually:

1. starts a tunnel client
2. uses one resolver from your list
3. waits for the tunnel to become usable
4. sends a real HTTP request through the tunnel
5. prints only resolvers that really pass traffic

This is useful when you want to find resolvers that still have outside connectivity during heavy filtering or shutdown conditions.

## What Is A Resolver Here?

A resolver is the DNS server IP you want to test.

Examples:

```txt
1.1.1.1
8.8.8.8:53
10.10.34.1
```

If you give only an IP, F35 uses port `53` automatically.

## What You Need Before Running

You need all of these:

- a file with resolver IPs
- a working tunnel domain
- one tunnel client:
  - `dnstt-client`
  - `slipstream-client`
  - `vaydns-client`
- the extra flags that your tunnel client needs, passed with `--args`

## Build

```bash
CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o f35 ./cmd/f35
```

## Quick Start

If you are new, start with the smallest common command:

```bash
f35 --resolvers resolvers.txt --domain t.example.com --args '-pubkey YOUR_PUBLIC_KEY'
```

This uses the default engine, which is `vaydns`.

On Windows PowerShell, if `vaydns-client.exe` is not in `PATH`, use `--client-path`:

```powershell
.\f35.exe --resolvers resolvers.txt --domain t.example.com --client-path .\vaydns-client.exe --args '-pubkey YOUR_PUBLIC_KEY'
```

## Config File

If you do not want to pass many flags every time, use a TOML config file.
Use flat keys with underscores, like `download_timeout` and `start_port`:

If you are new, this is the easiest way to use F35.
Write your settings once, then run `f35 -c f35.toml`.
For repeated scans, prefer a config file over a long flag list.

Use this as a starting point and remove the parts you do not need:

```toml
resolvers_file = "resolvers.txt"
engine = "vaydns"
client_path = "./vaydns-client"
domain = "t.example.com"
args = "-pubkey YOUR_PUBLIC_KEY"

probe = true
probe_url = "http://www.google.com/gen_204"
probe_timeout = 15

download = false
download_url = "https://speed.cloudflare.com/__down?bytes=100000"
download_timeout = 15

upload = false
upload_url = "https://speed.cloudflare.com/__up"
upload_bytes = 100000
upload_timeout = 15

whois = false
whois_timeout = 15

proxy = "socks5h"
proxy_user = ""
proxy_pass = ""

workers = 20
retries = 0
wait = 1000
start_port = 40000

json = false
short = false
quiet = false
```

What to edit first:

- `resolvers_file`
  your resolver list file
- `domain`
  your tunnel domain
- `args`
  your tunnel client flags, usually including the public key
- `client_path`
  set this if the client binary is not in `PATH`

Simple run:

```bash
f35 -c f35.toml
```

CLI flags override the file:

```bash
f35 -c f35.toml --workers 50 --download
```

## Flags

If you are new, focus on `--resolvers`, `--domain`, `--args`, `--config` or `-c`, and sometimes `--client-path`.

Use `--args` for tunnel-client-specific flags like `-pubkey`, and always wrap the whole value in quotes.
Example: `--args "-pubkey YOUR_PUBLIC_KEY"`.

### Required And Core Flags

| Flag | Default | Meaning |
| --- | --- | --- |
| `--config`, `-c` | none | Load a TOML config file. File values become defaults and CLI flags override them. |
| `--resolvers` | required | Path to a file containing resolver IPs. |
| `--domain` | required | Tunnel domain to test against. |
| `--args` | none | Extra tunnel client flags. This is where engine-specific flags like `-pubkey` go. |
| `--engine` | `vaydns` | Tunnel client engine: `dnstt`, `slipstream`, or `vaydns`. |
| `--client-path` | `PATH` lookup | Explicit path to the tunnel client binary if it is not in `PATH`. Useful on Windows. |

### Check Flags

| Flag | Default | Meaning |
| --- | --- | --- |
| `--probe` | `true` | Run a quick connectivity probe through the tunnel. |
| `--probe-url` | `http://www.google.com/gen_204` | HTTP URL used for the probe request. |
| `--probe-timeout` | `15` | Probe request timeout in seconds. |
| `--download` | `false` | Run a real download test through the tunnel. |
| `--download-url` | `https://speed.cloudflare.com/__down?bytes=100000` | HTTP URL used for the download test. |
| `--download-timeout` | `15` | Download request timeout in seconds. |
| `--upload` | `false` | Run a real upload test through the tunnel. |
| `--upload-url` | `https://speed.cloudflare.com/__up` | HTTP URL used for the upload test. |
| `--upload-bytes` | `100000` | Number of bytes sent in the upload body. |
| `--upload-timeout` | `15` | Upload request timeout in seconds. |
| `--whois` | `false` | Look up resolver organization and country. |
| `--whois-timeout` | `15` | Whois lookup timeout in seconds. |

### Scan Settings

| Flag | Default | Meaning |
| --- | --- | --- |
| `--proxy` | `socks5h` | Proxy protocol used for the HTTP request through the tunnel. Wrong values can make healthy resolvers look dead. |
| `--proxy-user` | none | Proxy username if the tunnel exit requires authentication. |
| `--proxy-pass` | none | Proxy password if the tunnel exit requires authentication. Requires `--proxy-user`. |
| `--workers` | `20` | Number of concurrent E2E scan workers. |
| `--wait` | `1000` | Milliseconds to wait for tunnel establishment before HTTP tests. |
| `--retries` | `0` | Retry count after the first failed E2E attempt. |
| `--start-port` | `40000` | First local listen port used for worker listener allocation. |

### Output Flags

| Flag | Default | Meaning |
| --- | --- | --- |
| `--json` | `false` | Print one JSON object per result line instead of plain text. |
| `--short` | `false` | Print only `IP:PORT LATENCY` in plain text output. |
| `--quiet` | `false` | Suppress startup, progress, and completion logs. |

## Timeout Tuning

Use these as the main knobs:

- `--wait`
  wait longer here if the tunnel starts too slowly
- `--probe-timeout`
  raise this if the quick probe is timing out
- `--download-timeout`
  raise this if the download test starts but does not finish in time
- `--upload-timeout`
  raise this if the upload test starts but does not finish in time
- `--whois-timeout`
  raise this if the whois lookup is too slow

Good starting values:

- very large resolver list: lower `--workers` if the tunnel client starts struggling
- slow tunnel startup: increase `--wait`
- weak or filtered path: increase `--download-timeout`
- weak upload path: increase `--upload-timeout`
- slow whois API: increase `--whois-timeout`
- only probe fails: increase `--probe-timeout`

## How `--args` Works

`--args` is only for tunnel client flags.

Put the same flags there that you normally pass when you run the tunnel client manually.
F35 does not replace your client config. It only fills in the resolver, listen address, and domain for you.

Always wrap the whole `--args` value in quotes.

- Linux and macOS:
  `--args '-pubkey YOUR_PUBLIC_KEY'`
- Windows PowerShell:
  `--args '-pubkey YOUR_PUBLIC_KEY'`
  `--args "-pubkey YOUR_PUBLIC_KEY"` also works
- Windows `cmd.exe`:
  use double quotes
  `--args "-pubkey YOUR_PUBLIC_KEY"`

Examples:

- DNSTT:
  `--args '-pubkey YOUR_PUBLIC_KEY'`
- VayDNS:
  `--args '-pubkey YOUR_PUBLIC_KEY -log-level info -udp-timeout 200ms'`
- Windows `cmd.exe`:
  `--args "-pubkey YOUR_PUBLIC_KEY"`
- Windows PowerShell:
  `--args '-pubkey YOUR_PUBLIC_KEY'`

F35 automatically fills these parts for you:

- resolver address
- local listen address
- domain

For `dnstt`, F35 places `--args` before the positional `domain` and `listen` arguments.

## First Real Example

If you are new, start with something like this:

```bash
f35 --resolvers resolvers.txt --engine dnstt --domain t.example.com --proxy socks5h --args '-pubkey YOUR_PUBLIC_KEY'
```

What this means:

- read resolvers from `resolvers.txt`
- use `dnstt-client`
- connect to `t.example.com`
- send the HTTP test through the tunnel using the `socks5h` protocol
- pass the public key to the client

## More Examples

### DNSTT

```bash
f35 --resolvers resolvers.txt \
  --engine dnstt \
  --domain t.example.com \
  --proxy socks5h \
  --args '-pubkey YOUR_PUBLIC_KEY'
```

### VayDNS

```bash
f35 --resolvers resolvers.txt \
  --engine vaydns \
  --domain t.example.com \
  --proxy socks5h \
  --args '-pubkey YOUR_PUBLIC_KEY -record-type txt -clientid-size 1 -rps 300 -max-qname-len 99 -max-num-labels 2'
```

### Slipstream

```bash
f35 --resolvers resolvers.txt \
  --engine slipstream \
  --domain t.example.com \
  --proxy socks5h
```

### Proxy Auth With `--proxy-user` And `--proxy-pass`

Use this if the proxy exposed by your tunnel requires a username and password:

```bash
f35 --resolvers resolvers.txt \
  --engine dnstt \
  --domain t.example.com \
  --proxy socks5h \
  --proxy-user myuser \
  --proxy-pass mypass \
  --args '-pubkey YOUR_PUBLIC_KEY'
```

`--proxy-pass` only works together with `--proxy-user`.

### Save Only Healthy Resolvers

```bash
f35 --resolvers resolvers.txt --engine dnstt --domain t.example.com --proxy socks5h --args '-pubkey YOUR_PUBLIC_KEY' | tee healthy.txt
```

### Use A Binary That Is Not In PATH

Linux or macOS:

```bash
f35 --resolvers resolvers.txt --engine vaydns --domain t.example.com --proxy socks5h --client-path ./vaydns-client --args '-pubkey YOUR_PUBLIC_KEY'
```

Windows PowerShell:

```powershell
.\f35.exe --resolvers resolvers.txt --domain t.example.com --client-path .\vaydns-client.exe --args '-pubkey YOUR_PUBLIC_KEY'
```

Windows full path example:

```powershell
.\f35.exe --resolvers resolvers.txt --domain t.example.com --client-path C:\tools\vaydns-client.exe --args '-pubkey YOUR_PUBLIC_KEY'
```

### Make The Scan More Conservative

This is useful when resolvers are slow but still usable.

```bash
f35 --resolvers resolvers.txt --engine vaydns --domain t.example.com --proxy socks5h --workers 50 --wait 2000 --probe-timeout 8 --retries 2 --args '-pubkey YOUR_PUBLIC_KEY'
```

Meaning:

- fewer concurrent workers
- longer tunnel warm-up wait
- longer HTTP timeout
- retry failed resolvers

### Show Resolver Owner Info

```bash
f35 --resolvers resolvers.txt --engine vaydns --domain t.example.com --proxy socks5h --whois --args '-pubkey YOUR_PUBLIC_KEY'
```

This keeps the enabled checks independent, and if `--whois` is enabled, plain output also includes org and country fields for that resolver IP.

This is most useful when the resolver IP itself belongs to the network you care about.
If your tunnel goes into a more advanced upstream chain, this extra lookup can be less meaningful.

### Add Upload Testing

```bash
f35 --resolvers resolvers.txt --engine vaydns --domain t.example.com --proxy socks5h --download --upload --args '-pubkey YOUR_PUBLIC_KEY'
```

This adds a real upload request to the scan and keeps it independent from the other checks.
By default it sends `100000` bytes with a `POST`, and you can change that with `--upload-bytes`.

### JSON Output

```bash
f35 --resolvers resolvers.txt --engine vaydns --domain t.example.com --proxy socks5h --whois --upload --json --args '-pubkey YOUR_PUBLIC_KEY'
```

Use this if you want to parse the output in another program.

## Important Note About Advanced Upstreams

F35 does not generate advanced proxy protocol packets by itself.
It only sends a normal HTTP request through the tunnel using the protocol selected with `--proxy`.

Examples:

- if your tunnel path expects SOCKS, use `--proxy socks5` or `--proxy socks5h`
- if your tunnel path expects HTTP proxy traffic, use `--proxy http`

If you use something more advanced behind the tunnel, like `vless+ws`, F35 is not generating native `vless+ws` traffic.
It is only checking whether the tunnel path can move a request and return any response.

That means:

- the download request is the strongest signal
- upload is the next strongest signal after download
- whois and probe are weaker checks
- F35 does not require HTTP `200`
- even `400` or `404` can still prove that the tunnel is working
- `--whois` may be less useful in those advanced chains
- wrong `--proxy` can ruin scan results

## Output

By default, F35 also prints colored status logs to `stderr`.
Use `--quiet` to silence those logs and keep only result lines on `stdout`.

On interactive terminals, the progress status updates in place on a single line so healthy resolver output stays visible above it.

Typical status logs look like this:

```txt
[INFO] starting | resolvers=5000 | workers=20 | engine=vaydns
[INFO] 50/5000 | healthy=11 | failed=39 | elapsed=28s
[INFO] completed | 5000/5000 | healthy=241 | failed=4759 | elapsed=2m14s
```

If no resolver passes, the final status line is printed as `[WARN]`.

### Normal Output

```txt
1.2.3.4:53 342ms download="off" upload="off" whois="off" probe="ok"
5.6.7.8:53 89ms download="off" upload="off" whois="off" probe="ok"
```

Only usable resolvers are printed.

A resolver is considered usable if at least one enabled check succeeds. By default, probe is the primary signal.
When more than one enabled check succeeds, latency priority is `download > upload > whois > probe`.
F35 does not require HTTP `200`.
Even a `400` or `404` can still prove that the tunnel is working.

Latency is colored on terminal output:

- green: `0-2000ms`
- yellow: `2000-6000ms`
- red: `6000ms+`

If you pipe the output to a file or another command, colors are not printed.

### Output With Checks

```txt
1.2.3.4:53 342ms download="ok" upload="ok" whois="ok" probe="fail" org="Iran Information Technology Company PJSC" country="Iran"
5.6.7.8:53 2140ms download="ok" upload="fail" whois="fail" probe="ok" org="" country=""
```

The output stays simple and the status fields always appear in the same order. When `--whois` is enabled, `org` and `country` are appended at the end.

### Output With `--short`

```txt
1.2.3.4:53 342ms
5.6.7.8:53 89ms
```

### Output With `--json`

```json
{"resolver":"1.2.3.4:53","latency_ms":342,"download":"off","upload":"off","whois":"off","probe":"ok"}
{"resolver":"5.6.7.8:53","latency_ms":2140,"download":"ok","upload":"fail","whois":"fail","probe":"ok"}
```

## Good Defaults For New Users

If you do not know what to tune first, try this order:

1. keep `--proxy socks5h`
2. if output is empty, increase `--wait`
3. if working resolvers are slow, increase `--probe-timeout`
4. if results are unstable, lower `--workers`
5. if some resolvers fail randomly, add `--retries 1` or `--retries 2`

## Troubleshooting

### `binary ... not found in PATH`

The selected tunnel client binary was not found.

Fix it with one of these:

- install the client
- add it to `PATH`
- use `--client-path /full/path/to/client`
- on Windows, a common fix is `--client-path .\vaydns-client.exe`
- on Windows, a full path also works, for example `--client-path C:\tools\vaydns-client.exe`

### No Output

Usually one of these is wrong:

- domain
- engine
- pubkey or other tunnel client flags inside `--args`
- wait time is too short
- probe timeout is too short

Try this:

```bash
--wait 2000 --probe-timeout 8 --retries 1
```

### `--proxy-pass requires --proxy-user`

If you set a proxy password, you must also set a proxy username.

### Very Few Working Resolvers

Try:

- lower `--workers`
- increase `--wait`
- increase `--probe-timeout`
- add retries with `--retries`

### I Do Not Know What To Put In `--args`

Put the same client flags you normally use when running your tunnel client manually.

F35 is not replacing your tunnel client config.
It is only fuzzing resolvers and local listen ports around that client command.

## Project Structure

- root package `github.com/nxdp/f35`
  importable scanner library
- `./cmd/f35`
  CLI entrypoint

## Use As Library

```go
package main

import (
	"fmt"

	"github.com/nxdp/f35"
)

func main() {
	cfg := f35.DefaultConfig()
	cfg.Domain = "t.example.com"
	cfg.Resolvers = []string{"1.1.1.1:53", "8.8.8.8:53"}
	cfg.Upload = true
	cfg.ExtraArgs = []string{"-pubkey", "YOUR_PUBLIC_KEY"}

	err := f35.Scan(cfg, f35.Hooks{
		OnResult: func(result f35.Result) {
			fmt.Println(result.Resolver, result.LatencyMS)
		},
	})
	if err != nil {
		panic(err)
	}
}
```

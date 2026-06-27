use std::ffi::CString;
use std::io;
use std::net::Ipv4Addr;
use std::os::fd::{AsRawFd, FromRawFd, OwnedFd};

use tokio::process::Command;
use tokio::task::JoinHandle;
use tokio_util::sync::CancellationToken;

use crate::report;
use vpnhotspotd::shared::proto::daemon;

const ETH_P_IP: u16 = 0x0800;
const ETH_P_ALL: u16 = 0x0003;
const DHCP_SERVER_PORT: u16 = 67;
const DHCP_CLIENT_PORT: u16 = 68;
const BOOTREQUEST: u8 = 1;
const BOOTREPLY: u8 = 2;
const DHCP_DISCOVER: u8 = 1;
const DHCP_OFFER: u8 = 2;
const DHCP_REQUEST: u8 = 3;
const DHCP_ACK: u8 = 5;

#[derive(Clone)]
struct Config {
    dev: String,
    ifindex: i32,
    server: Ipv4Addr,
    mask: Ipv4Addr,
    network: u32,
    broadcast: u32,
    mac: [u8; 6],
    lease_seconds: u32,
}

pub(crate) struct ServerState {
    dev: String,
    cancel: CancellationToken,
    task: JoinHandle<io::Result<()>>,
}

impl ServerState {
    pub(crate) async fn stop(self) {
        self.cancel.cancel();
        match self.task.await {
            Ok(Ok(())) => {}
            Ok(Err(e)) => report::io("dhcp.server", e),
            Err(e) => report::message("dhcp.server_join", e.to_string(), "JoinError"),
        }
        delete_block_rule(&self.dev).await;
    }
}

pub(crate) async fn start(command: daemon::RunDhcpServerCommand) -> io::Result<ServerState> {
    let cfg = Config::from_command(command)?;
    delete_block_rule(&cfg.dev).await;
    install_block_rule(&cfg.dev).await?;
    let fd = bind_packet_socket(&cfg)?;
    let cancel = CancellationToken::new();
    let task_cancel = cancel.clone();
    let dev = cfg.dev.clone();
    let task = tokio::task::spawn_blocking(move || run_loop(fd, cfg, task_cancel));
    Ok(ServerState { dev, cancel, task })
}

pub(crate) async fn stop(dev: &str) {
    delete_block_rule(dev).await;
}

impl Config {
    fn from_command(command: daemon::RunDhcpServerCommand) -> io::Result<Self> {
        let dev = command.dev.trim().to_string();
        if dev.is_empty() {
            return Err(io::Error::new(io::ErrorKind::InvalidInput, "missing DHCP interface"));
        }
        let server = command.server.ok_or_else(|| {
            io::Error::new(io::ErrorKind::InvalidInput, "missing DHCP server address")
        })?;
        if server.address.len() != 4 {
            return Err(io::Error::new(io::ErrorKind::InvalidInput, "DHCP server address is not IPv4"));
        }
        if server.prefix_length == 0 || server.prefix_length > 30 {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                "DHCP prefix must be between /1 and /30",
            ));
        }
        let server_ip = Ipv4Addr::new(
            server.address[0],
            server.address[1],
            server.address[2],
            server.address[3],
        );
        let server_u32 = u32::from(server_ip);
        let mask_u32 = u32::MAX << (32 - server.prefix_length);
        let network = server_u32 & mask_u32;
        let broadcast = network | !mask_u32;
        Ok(Config {
            ifindex: ifindex(&dev)?,
            mac: read_mac(&dev)?,
            dev,
            server: server_ip,
            mask: Ipv4Addr::from(mask_u32),
            network,
            broadcast,
            lease_seconds: command.lease_seconds.max(60),
        })
    }

    fn contains_client(&self, ip: Ipv4Addr) -> bool {
        let value = u32::from(ip);
        value & u32::from(self.mask) == self.network
            && value != self.network
            && value != self.broadcast
            && ip != self.server
    }

    fn choose_client_ip(&self, mac: &[u8], requested: Option<Ipv4Addr>) -> Ipv4Addr {
        if let Some(requested) = requested {
            if self.contains_client(requested) {
                return requested;
            }
        }
        let first = self.network.saturating_add(2);
        let last = self.broadcast.saturating_sub(1);
        let pool = last.saturating_sub(first).max(1);
        let hash = mac.iter().fold(0u32, |acc, value| acc.wrapping_mul(257).wrapping_add(*value as u32));
        let mut ip = Ipv4Addr::from(first + hash % pool);
        if ip == self.server {
            ip = Ipv4Addr::from(first + (hash + 1) % pool);
        }
        ip
    }
}

fn ifindex(dev: &str) -> io::Result<i32> {
    let name = CString::new(dev).map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "invalid interface name"))?;
    let index = unsafe { libc::if_nametoindex(name.as_ptr()) };
    if index == 0 {
        Err(io::Error::last_os_error())
    } else {
        Ok(index as i32)
    }
}

fn read_mac(dev: &str) -> io::Result<[u8; 6]> {
    let text = std::fs::read_to_string(format!("/sys/class/net/{dev}/address"))?;
    let parts = text.trim().split(':').collect::<Vec<_>>();
    if parts.len() != 6 {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "invalid interface MAC address"));
    }
    let mut mac = [0u8; 6];
    for (i, part) in parts.iter().enumerate() {
        mac[i] = u8::from_str_radix(part, 16)
            .map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "invalid interface MAC address"))?;
    }
    Ok(mac)
}

fn htons(value: u16) -> u16 {
    value.to_be()
}

fn bind_packet_socket(cfg: &Config) -> io::Result<OwnedFd> {
    let fd = unsafe { libc::socket(libc::AF_PACKET, libc::SOCK_RAW | libc::SOCK_CLOEXEC, htons(ETH_P_ALL) as i32) };
    if fd < 0 {
        return Err(io::Error::last_os_error());
    }
    let fd = unsafe { OwnedFd::from_raw_fd(fd) };

    let timeout = libc::timeval { tv_sec: 1, tv_usec: 0 };
    let result = unsafe {
        libc::setsockopt(
            fd.as_raw_fd(),
            libc::SOL_SOCKET,
            libc::SO_RCVTIMEO,
            &timeout as *const _ as *const libc::c_void,
            std::mem::size_of_val(&timeout) as libc::socklen_t,
        )
    };
    if result < 0 {
        return Err(io::Error::last_os_error());
    }

    let mut addr: libc::sockaddr_ll = unsafe { std::mem::zeroed() };
    addr.sll_family = libc::AF_PACKET as u16;
    addr.sll_protocol = htons(ETH_P_IP);
    addr.sll_ifindex = cfg.ifindex;
    let result = unsafe {
        libc::bind(
            fd.as_raw_fd(),
            &addr as *const _ as *const libc::sockaddr,
            std::mem::size_of_val(&addr) as libc::socklen_t,
        )
    };
    if result < 0 {
        return Err(io::Error::last_os_error());
    }
    Ok(fd)
}

fn run_loop(fd: OwnedFd, cfg: Config, cancel: CancellationToken) -> io::Result<()> {
    let mut buffer = [0u8; 2048];
    while !cancel.is_cancelled() {
        let len = unsafe {
            libc::recv(
                fd.as_raw_fd(),
                buffer.as_mut_ptr() as *mut libc::c_void,
                buffer.len(),
                0,
            )
        };
        if len < 0 {
            let e = io::Error::last_os_error();
            if matches!(e.kind(), io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut) {
                continue;
            }
            return Err(e);
        }
        if let Some(reply) = build_reply(&cfg, &buffer[..len as usize]) {
            send_packet(&fd, cfg.ifindex, &reply)?;
        }
    }
    Ok(())
}

fn send_packet(fd: &OwnedFd, ifindex: i32, packet: &[u8]) -> io::Result<()> {
    let mut addr: libc::sockaddr_ll = unsafe { std::mem::zeroed() };
    addr.sll_family = libc::AF_PACKET as u16;
    addr.sll_ifindex = ifindex;
    addr.sll_halen = 6;
    addr.sll_addr[..6].copy_from_slice(&packet[..6]);
    let sent = unsafe {
        libc::sendto(
            fd.as_raw_fd(),
            packet.as_ptr() as *const libc::c_void,
            packet.len(),
            0,
            &addr as *const _ as *const libc::sockaddr,
            std::mem::size_of_val(&addr) as libc::socklen_t,
        )
    };
    if sent < 0 {
        Err(io::Error::last_os_error())
    } else {
        Ok(())
    }
}

fn build_reply(cfg: &Config, frame: &[u8]) -> Option<Vec<u8>> {
    if frame.len() < 14 + 20 + 8 + 240 {
        return None;
    }
    if frame[12] != 0x08 || frame[13] != 0x00 {
        return None;
    }
    let ip = 14;
    if frame[ip] >> 4 != 4 {
        return None;
    }
    let ihl = ((frame[ip] & 0x0f) as usize) * 4;
    if ihl < 20 || frame.len() < ip + ihl + 8 {
        return None;
    }
    if frame[ip + 9] != 17 {
        return None;
    }
    let udp = ip + ihl;
    let src_port = u16::from_be_bytes([frame[udp], frame[udp + 1]]);
    let dst_port = u16::from_be_bytes([frame[udp + 2], frame[udp + 3]]);
    if src_port != DHCP_CLIENT_PORT || dst_port != DHCP_SERVER_PORT {
        return None;
    }
    let dhcp = udp + 8;
    if frame.len() < dhcp + 240 || frame[dhcp] != BOOTREQUEST {
        return None;
    }
    if frame[dhcp + 1] != 1 || frame[dhcp + 2] != 6 {
        return None;
    }
    if frame[dhcp + 236..dhcp + 240] != [99, 130, 83, 99] {
        return None;
    }

    let options = &frame[dhcp + 240..];
    let message_type = option_value(options, 53).and_then(|value| value.first().copied())?;
    let response_type = match message_type {
        DHCP_DISCOVER => DHCP_OFFER,
        DHCP_REQUEST => DHCP_ACK,
        _ => return None,
    };

    let client_mac = &frame[dhcp + 28..dhcp + 34];
    let requested = option_value(options, 50)
        .filter(|value| value.len() == 4)
        .map(|value| Ipv4Addr::new(value[0], value[1], value[2], value[3]))
        .or_else(|| {
            let ciaddr = Ipv4Addr::new(frame[dhcp + 12], frame[dhcp + 13], frame[dhcp + 14], frame[dhcp + 15]);
            if ciaddr == Ipv4Addr::UNSPECIFIED { None } else { Some(ciaddr) }
        });
    let client_ip = cfg.choose_client_ip(client_mac, requested);
    Some(build_dhcp_packet(
        cfg,
        &frame[dhcp + 4..dhcp + 8],
        &frame[dhcp + 10..dhcp + 12],
        client_mac,
        client_ip,
        response_type,
    ))
}

fn option_value(options: &[u8], code: u8) -> Option<&[u8]> {
    let mut i = 0;
    while i < options.len() {
        let current = options[i];
        i += 1;
        match current {
            0 => continue,
            255 => break,
            _ => {
                if i >= options.len() {
                    break;
                }
                let len = options[i] as usize;
                i += 1;
                if i + len > options.len() {
                    break;
                }
                if current == code {
                    return Some(&options[i..i + len]);
                }
                i += len;
            }
        }
    }
    None
}

fn build_dhcp_packet(
    cfg: &Config,
    xid: &[u8],
    flags: &[u8],
    client_mac: &[u8],
    client_ip: Ipv4Addr,
    message_type: u8,
) -> Vec<u8> {
    let mut dhcp = vec![0u8; 240];
    dhcp[0] = BOOTREPLY;
    dhcp[1] = 1;
    dhcp[2] = 6;
    dhcp[3] = 0;
    dhcp[4..8].copy_from_slice(xid);
    dhcp[10..12].copy_from_slice(flags);
    dhcp[16..20].copy_from_slice(&client_ip.octets());
    dhcp[20..24].copy_from_slice(&cfg.server.octets());
    dhcp[28..34].copy_from_slice(client_mac);
    dhcp[236..240].copy_from_slice(&[99, 130, 83, 99]);

    push_option(&mut dhcp, 53, &[message_type]);
    push_option(&mut dhcp, 54, &cfg.server.octets());
    push_option(&mut dhcp, 1, &cfg.mask.octets());
    push_option(&mut dhcp, 3, &cfg.server.octets());
    push_option(&mut dhcp, 6, &cfg.server.octets());
    push_option(&mut dhcp, 28, &Ipv4Addr::from(cfg.broadcast).octets());
    push_option(&mut dhcp, 51, &cfg.lease_seconds.to_be_bytes());
    push_option(&mut dhcp, 58, &(cfg.lease_seconds / 2).to_be_bytes());
    push_option(&mut dhcp, 59, &(cfg.lease_seconds * 7 / 8).to_be_bytes());
    dhcp.push(255);
    while dhcp.len() < 300 {
        dhcp.push(0);
    }

    build_ipv4_udp_frame(cfg, dhcp)
}

fn push_option(packet: &mut Vec<u8>, code: u8, value: &[u8]) {
    packet.push(code);
    packet.push(value.len() as u8);
    packet.extend_from_slice(value);
}

fn build_ipv4_udp_frame(cfg: &Config, payload: Vec<u8>) -> Vec<u8> {
    let total_len = 20 + 8 + payload.len();
    let mut frame = vec![0u8; 14 + total_len];

    frame[0..6].fill(0xff);
    frame[6..12].copy_from_slice(&cfg.mac);
    frame[12..14].copy_from_slice(&ETH_P_IP.to_be_bytes());

    let ip = 14;
    frame[ip] = 0x45;
    frame[ip + 1] = 0;
    frame[ip + 2..ip + 4].copy_from_slice(&(total_len as u16).to_be_bytes());
    frame[ip + 4..ip + 6].copy_from_slice(&0u16.to_be_bytes());
    frame[ip + 6..ip + 8].copy_from_slice(&0u16.to_be_bytes());
    frame[ip + 8] = 64;
    frame[ip + 9] = 17;
    frame[ip + 12..ip + 16].copy_from_slice(&cfg.server.octets());
    frame[ip + 16..ip + 20].copy_from_slice(&Ipv4Addr::BROADCAST.octets());
    let sum = checksum(&frame[ip..ip + 20]);
    frame[ip + 10..ip + 12].copy_from_slice(&sum.to_be_bytes());

    let udp = ip + 20;
    frame[udp..udp + 2].copy_from_slice(&DHCP_SERVER_PORT.to_be_bytes());
    frame[udp + 2..udp + 4].copy_from_slice(&DHCP_CLIENT_PORT.to_be_bytes());
    frame[udp + 4..udp + 6].copy_from_slice(&((8 + payload.len()) as u16).to_be_bytes());
    frame[udp + 6..udp + 8].copy_from_slice(&0u16.to_be_bytes());
    frame[udp + 8..].copy_from_slice(&payload);
    frame
}

fn checksum(bytes: &[u8]) -> u16 {
    let mut sum = 0u32;
    for chunk in bytes.chunks(2) {
        let value = if chunk.len() == 2 {
            u16::from_be_bytes([chunk[0], chunk[1]]) as u32
        } else {
            (chunk[0] as u32) << 8
        };
        sum = sum.wrapping_add(value);
    }
    while sum >> 16 != 0 {
        sum = (sum & 0xffff) + (sum >> 16);
    }
    !(sum as u16)
}

async fn install_block_rule(dev: &str) -> io::Result<()> {
    run_iptables(&[
        "-I", "OUTPUT", "1",
        "-o", dev,
        "-p", "udp",
        "--sport", "67",
        "--dport", "68",
        "-j", "DROP",
    ]).await
}

async fn delete_block_rule(dev: &str) {
    for _ in 0..16 {
        if run_iptables(&[
            "-D", "OUTPUT",
            "-o", dev,
            "-p", "udp",
            "--sport", "67",
            "--dport", "68",
            "-j", "DROP",
        ]).await.is_err() {
            break;
        }
    }
}

async fn run_iptables(args: &[&str]) -> io::Result<()> {
    let output = Command::new("iptables").args(args).output().await?;
    if output.status.success() {
        Ok(())
    } else {
        Err(io::Error::other(format!(
            "iptables {} failed: {}{}",
            args.join(" "),
            String::from_utf8_lossy(&output.stdout),
            String::from_utf8_lossy(&output.stderr),
        )))
    }
}

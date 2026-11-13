#!/usr/bin/env node
/**
 * 最小 RCON 客户端（Minecraft Java 版协议）。
 *
 * 用途：从外部给服务端发命令。`gradlew runServer` 会吞掉 stdin，所以 `/gtsnperf`
 * 这类命令没法通过管道送进去，只能走 RCON。
 *
 * 用法：
 *   node tools/rcon.mjs "summon minecraft:zombie 0 64 0"
 *   node tools/rcon.mjs --wait 120 "gtsnperf"
 *   echo "命令1
 *   命令2" | node tools/rcon.mjs --batch      # 一条连接跑多句，摆方块时快得多
 *
 * 协议：length(int32LE) | requestId(int32LE) | type(int32LE) | body | \0\0
 *   type 3 = login, 2 = command, 0 = response
 */
import net from 'node:net';

const HOST = process.env.RCON_HOST || '127.0.0.1';
const PORT = Number(process.env.RCON_PORT || 25575);
const PASSWORD = process.env.RCON_PASSWORD || 'gtsnkernel';

function encode(requestId, type, body) {
  const payload = Buffer.from(body, 'utf8');
  const buffer = Buffer.alloc(14 + payload.length);
  buffer.writeInt32LE(10 + payload.length, 0);
  buffer.writeInt32LE(requestId, 4);
  buffer.writeInt32LE(type, 8);
  payload.copy(buffer, 12);
  buffer.writeInt16LE(0, 12 + payload.length);
  return buffer;
}

function decode(chunk) {
  const length = chunk.readInt32LE(0);
  const requestId = chunk.readInt32LE(4);
  const type = chunk.readInt32LE(8);
  const body = chunk.subarray(12, 4 + length - 2).toString('utf8');
  return { requestId, type, body };
}

function readStdin() {
  return new Promise((resolve) => {
    let data = '';
    process.stdin.setEncoding('utf8');
    process.stdin.on('data', (chunk) => (data += chunk));
    process.stdin.on('end', () => resolve(data));
  });
}

/** 单条命令。 */
function once(command, timeoutMs) {
  return new Promise((resolve, reject) => {
    const socket = net.connect({ host: HOST, port: PORT });
    const timer = setTimeout(() => {
      socket.destroy();
      reject(new Error('timeout'));
    }, timeoutMs);
    let buffer = Buffer.alloc(0);
    let loggedIn = false;

    socket.on('connect', () => socket.write(encode(1, 3, PASSWORD)));
    socket.on('data', (data) => {
      buffer = Buffer.concat([buffer, data]);
      while (buffer.length >= 4) {
        const length = buffer.readInt32LE(0);
        if (buffer.length < 4 + length) break;
        const packet = buffer.subarray(0, 4 + length);
        buffer = buffer.subarray(4 + length);
        const { requestId, body } = decode(packet);
        if (!loggedIn) {
          if (requestId === -1) {
            clearTimeout(timer);
            socket.destroy();
            reject(new Error('auth failed'));
            return;
          }
          loggedIn = true;
          socket.write(encode(2, 2, command));
        } else {
          clearTimeout(timer);
          socket.destroy();
          resolve(body);
        }
      }
    });
    socket.on('error', (error) => {
      clearTimeout(timer);
      reject(error);
    });
  });
}

/**
 * 批量：一条连接按顺序发完所有命令。
 *
 * 摆几百个方块时这个快一个数量级 —— 每条命令新建 TCP 连接的话，500 条要 30 秒以上。
 *
 * @returns 失败条数（响应里含 error / Unknown / No blocks 之类）
 */
function batch(commands, timeoutMs) {
  return new Promise((resolve, reject) => {
    const socket = net.connect({ host: HOST, port: PORT });
    const timer = setTimeout(() => {
      socket.destroy();
      reject(new Error('timeout'));
    }, timeoutMs);
    let buffer = Buffer.alloc(0);
    let loggedIn = false;
    let index = 0;
    let failures = 0;

    const sendNext = () => {
      if (index >= commands.length) {
        clearTimeout(timer);
        socket.destroy();
        resolve(failures);
        return;
      }
      socket.write(encode(2, 2, commands[index++]));
    };

    socket.on('connect', () => socket.write(encode(1, 3, PASSWORD)));
    socket.on('data', (data) => {
      buffer = Buffer.concat([buffer, data]);
      while (buffer.length >= 4) {
        const length = buffer.readInt32LE(0);
        if (buffer.length < 4 + length) break;
        const packet = buffer.subarray(0, 4 + length);
        buffer = buffer.subarray(4 + length);
        const { requestId, body } = decode(packet);
        if (!loggedIn) {
          if (requestId === -1) {
            clearTimeout(timer);
            socket.destroy();
            reject(new Error('auth failed'));
            return;
          }
          loggedIn = true;
          sendNext();
        } else {
          if (body && /error|Unknown|No blocks|Cannot|failed/i.test(body)) {
            failures++;
          }
          sendNext();
        }
      }
    });
    socket.on('error', (error) => {
      clearTimeout(timer);
      reject(error);
    });
  });
}

const argv = process.argv.slice(2);

if (argv.includes('--batch')) {
  const raw = await readStdin();
  const commands = raw.split('\n').map((line) => line.trim()).filter(Boolean);
  if (commands.length === 0) {
    console.error('[rcon] no commands on stdin');
    process.exit(2);
  }
  try {
    const failures = await batch(commands, 120000);
    console.error(`[rcon] ${commands.length} commands, ${failures} reported errors`);
    process.exit(failures > 0 ? 1 : 0);
  } catch (error) {
    console.error(`[rcon] batch failed: ${error.message}`);
    process.exit(1);
  }
}

let waitSeconds = 0;
const waitIndex = argv.indexOf('--wait');
if (waitIndex >= 0) {
  waitSeconds = Number(argv[waitIndex + 1] || 60);
  argv.splice(waitIndex, 2);
}
const command = argv.join(' ');

if (!command) {
  console.error('usage: node tools/rcon.mjs [--wait SECONDS] "<command>"');
  console.error('       echo "cmd1\\ncmd2" | node tools/rcon.mjs --batch');
  process.exit(2);
}

const deadline = Date.now() + waitSeconds * 1000;
let attempt = 0;

while (true) {
  attempt++;
  try {
    const response = await once(command, 15000);
    process.stdout.write(response);
    process.exit(0);
  } catch (error) {
    if (Date.now() >= deadline) {
      console.error(`[rcon] failed after ${attempt} attempts: ${error.message}`);
      process.exit(1);
    }
    await new Promise((r) => setTimeout(r, 2000));
  }
}

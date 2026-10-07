import { useEffect, useRef, useState } from 'react';
import { container } from '../../../core/container';
import type { AppConfig } from '../../../core/config/app-config';

/**
 * Demo for DemoBlock `realtime:chat` — topic 28 (realtime), the
 * websockets section. A NATIVE WebSocket (no library, no STOMP — that is
 * the point) against /ws/demo/chat:
 *
 * - ROOMS: the room travels as a handshake query parameter
 *   (?room=alpha|beta) — pick a room before connecting; two browser tabs
 *   in different rooms cannot hear each other (the isolation the server
 *   tests assert);
 * - NICKNAME: likewise a query param (?nick=…), sanitized server-side;
 * - SYSTEM FRAMES: join/leave arrive as {"type":"join"|"leave"} from
 *   "system"; says arrive as {"type":"say"} carrying the sender's nick;
 * - RECONNECT IS CLIENT DISCIPLINE here — unlike EventSource, a raw
 *   WebSocket stays dead after a drop: the badge shows closed and the
 *   connect button is the retry policy.
 *
 * The socket base differs from apiBaseUrl: the dev client is served by
 * Vite on 5176, and only /api is proxied — WebSocket connections go
 * straight to the server origin (VITE_WS_BASE_URL overrides for compose).
 */

const WS_BASE = import.meta.env.VITE_WS_BASE_URL ?? 'ws://localhost:8080';

type ConnectionState = 'idle' | 'connecting' | 'live' | 'closed';

interface ChatFrame {
  type: 'say' | 'join' | 'leave' | 'reject';
  from: string;
  text: string;
  room: string;
  at: string;
  seq: number;
}

interface RoomOccupancy {
  name: string;
  sessions: number;
}

const ROOMS = ['alpha', 'beta'] as const;

const OVERLINE = 'text-overline font-semibold uppercase tracking-[0.08em] text-on-surface-variant';
const INPUT =
  'rounded-sm border border-outline bg-surface px-xs py-xxs font-mono text-caption text-on-surface';
const CHIP =
  'rounded-pill border border-outline px-sm py-xxs font-mono text-caption text-on-surface-variant';

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

async function fetchChatState(config: AppConfig): Promise<RoomOccupancy[] | null> {
  try {
    const response = await fetch(`${config.apiBaseUrl}/demo/realtime/chat/state`);
    if (!response.ok) return null;
    const body: unknown = await response.json();
    if (!isRecord(body) || !Array.isArray(body.rooms)) return null;
    return body.rooms.map((row) => ({
      name: isRecord(row) && typeof row.name === 'string' ? row.name : '?',
      sessions: isRecord(row) && typeof row.sessions === 'number' ? row.sessions : 0,
    }));
  } catch {
    return null; // transient — the next poll replaces this answer
  }
}

export default function ChatDemo() {
  const config = container.appConfig;
  const [nick, setNick] = useState('ada');
  const [room, setRoom] = useState<string>('alpha');
  const [connection, setConnection] = useState<ConnectionState>('idle');
  const [frames, setFrames] = useState<ChatFrame[]>([]);
  const [draft, setDraft] = useState('');
  const [occupancy, setOccupancy] = useState<RoomOccupancy[] | null>(null);
  const socketRef = useRef<WebSocket | null>(null);

  const disconnect = () => {
    socketRef.current?.close();
    socketRef.current = null;
  };

  const connect = () => {
    disconnect();
    setConnection('connecting');
    // room and nick are query parameters on the handshake itself — the
    // server refuses unknown rooms (close 1008) instead of guessing
    const socket = new WebSocket(
      `${WS_BASE}/ws/demo/chat?room=${room}&nick=${encodeURIComponent(nick)}`,
    );
    socketRef.current = socket;
    socket.onopen = () => setConnection('live');
    socket.onmessage = (event) => {
      const frame = JSON.parse(String(event.data)) as ChatFrame;
      setFrames((previous) => [...previous, frame].slice(-30));
    };
    socket.onclose = () => setConnection('closed');
    void fetchChatState(config).then((answer) => {
      if (answer) setOccupancy(answer);
    });
  };

  useEffect(() => {
    if (connection !== 'live') {
      return;
    }
    const poll = window.setInterval(() => {
      void fetchChatState(config).then((answer) => {
        if (answer) setOccupancy(answer);
      });
    }, 2_000);
    return () => window.clearInterval(poll);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [connection === 'live']);

  useEffect(() => disconnect, []);

  const send = () => {
    const socket = socketRef.current;
    if (socket && socket.readyState === WebSocket.OPEN && draft.trim() !== '') {
      socket.send(JSON.stringify({ text: draft }));
      setDraft('');
    }
  };

  return (
    <div className="flex flex-col gap-md">
      <section className="flex flex-wrap items-end gap-sm">
        <label className="flex flex-col gap-xxs font-mono text-caption text-on-surface-variant">
          nick
          <input
            data-testid="realtime-chat-nick"
            value={nick}
            onChange={(event) => setNick(event.target.value)}
            className={`${INPUT} w-32`}
          />
        </label>
        <label className="flex flex-col gap-xxs font-mono text-caption text-on-surface-variant">
          room
          <select
            data-testid="realtime-chat-room"
            value={room}
            onChange={(event) => setRoom(event.target.value)}
            className={INPUT}
          >
            {ROOMS.map((name) => (
              <option key={name} value={name}>
                {name}
              </option>
            ))}
          </select>
        </label>
        <button
          type="button"
          data-testid="realtime-chat-connect"
          onClick={connect}
          className="rounded-pill border border-secondary bg-secondary/15 px-md py-xxs font-mono text-caption text-on-surface"
        >
          connect (WebSocket)
        </button>
        <button
          type="button"
          data-testid="realtime-chat-disconnect"
          onClick={disconnect}
          className="rounded-pill border border-outline px-sm py-xxs font-mono text-caption text-on-surface-variant"
        >
          close()
        </button>
        <span data-testid="realtime-chat-connection" className={CHIP}>
          {connection}
        </span>
      </section>

      <section className="flex flex-wrap items-center gap-sm">
        <p className={OVERLINE}>rooms (GET /chat/state)</p>
        {(occupancy ?? []).map((row) => (
          <span key={row.name} data-testid="realtime-chat-rooms" className={CHIP}>
            {row.name}: {String(row.sessions)}
          </span>
        ))}
      </section>

      <section className="flex flex-col gap-xs">
        <p className={OVERLINE}>room log (join/leave are system frames; ids are server-assigned)</p>
        <div
          data-testid="realtime-chat-log"
          className="flex min-h-40 flex-col gap-xxs rounded-sm border border-outline bg-surface px-sm py-xs font-mono text-caption text-on-surface"
        >
          {frames.length === 0 && (
            <span className="text-on-surface-variant">no frames — connect to join the room</span>
          )}
          {frames.map((frame) => (
            <span
              key={frame.seq}
              data-testid={`realtime-chat-frame-${frame.type}`}
              className={`break-all ${frame.type === 'say' ? '' : 'text-on-surface-variant'}`}
            >
              [{frame.type}] {frame.from}: {frame.text}
            </span>
          ))}
        </div>
        <div className="flex flex-wrap items-center gap-sm">
          <input
            data-testid="realtime-chat-draft"
            value={draft}
            onChange={(event) => setDraft(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === 'Enter') send();
            }}
            placeholder='{"text": …} is added for you'
            className={`${INPUT} flex-1`}
          />
          <button
            type="button"
            data-testid="realtime-chat-send"
            onClick={send}
            className="rounded-pill border border-secondary bg-secondary/15 px-md py-xxs font-mono text-caption text-on-surface"
          >
            send()
          </button>
        </div>
      </section>
    </div>
  );
}

import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen } from '@testing-library/react';
import ChatDemo from './chat_demo';
import { container } from '../../../core/container';
import { createAppConfig } from '../../../core/config/app-config';

/**
 * ChatDemo spec — the WebSocket class is FAKED (jsdom's own would try a
 * real socket) and the SERVER side is driven by hand: opening the socket
 * should have carried room + nick as handshake query params, system
 * join/leave frames and say frames render in the log, send() writes the
 * one-shape payload ({"text": …}), and closing drops the badge to closed
 * — the raw socket's no-auto-reconnect reality.
 */

interface ChatFrame {
  type: 'say' | 'join' | 'leave' | 'reject';
  from: string;
  text: string;
  room: string;
  at: string;
  seq: number;
}

class FakeWebSocket {
  static instances: FakeWebSocket[] = [];
  static readonly CONNECTING = 0;
  static readonly OPEN = 1;
  static readonly CLOSED = 3;

  readyState = 0;
  url: string;
  onopen: (() => void) | null = null;
  onmessage: ((event: { data: string }) => void) | null = null;
  onclose: (() => void) | null = null;
  readonly sent: string[] = [];

  constructor(url: string) {
    this.url = url;
    FakeWebSocket.instances.push(this);
  }

  send(payload: string): void {
    this.sent.push(payload);
  }

  close(): void {
    this.readyState = 3;
    this.onclose?.();
  }

  // ---- spec helpers: drive the fake the way the server would ----

  open(): void {
    this.readyState = 1;
    this.onopen?.();
  }

  serverFrame(frame: ChatFrame): void {
    this.onmessage?.({ data: JSON.stringify(frame) });
  }
}

function lastSocket(): FakeWebSocket {
  const instance = FakeWebSocket.instances.at(-1);
  if (!instance) throw new Error('no WebSocket constructed');
  return instance;
}

function stubStateFetch(rooms: { name: string; sessions: number }[]): void {
  vi.stubGlobal(
    'fetch',
    vi.fn(async () => ({
      ok: true,
      status: 200,
      json: async () => ({ rooms }),
    })),
  );
}

beforeAll(() => {
  container.initialize(createAppConfig());
});

afterEach(() => {
  vi.unstubAllGlobals();
  FakeWebSocket.instances = [];
});

describe('ChatDemo', () => {
  it('carries room and nick on the handshake and reaches live', async () => {
    stubStateFetch([
      { name: 'alpha', sessions: 1 },
      { name: 'beta', sessions: 0 },
    ]);
    vi.stubGlobal('WebSocket', FakeWebSocket);

    render(<ChatDemo />);
    fireEvent.change(screen.getByTestId('realtime-chat-nick'), { target: { value: 'grace' } });
    fireEvent.change(screen.getByTestId('realtime-chat-room'), { target: { value: 'beta' } });
    fireEvent.click(screen.getByTestId('realtime-chat-connect'));

    const socket = lastSocket();
    expect(socket.url).toBe('ws://localhost:8080/ws/demo/chat?room=beta&nick=grace');
    expect(screen.getByTestId('realtime-chat-connection')).toHaveTextContent('connecting');
    act(() => { socket.open(); });
    expect(screen.getByTestId('realtime-chat-connection')).toHaveTextContent('live');
    const chips = await screen.findAllByTestId('realtime-chat-rooms');
    expect(chips.map((chip) => chip.textContent)).toContain('beta: 0');
  });

  it('renders join, say and leave frames and sends the one-shape payload', () => {
    stubStateFetch([
      { name: 'alpha', sessions: 2 },
      { name: 'beta', sessions: 0 },
    ]);
    vi.stubGlobal('WebSocket', FakeWebSocket);

    render(<ChatDemo />);
    fireEvent.click(screen.getByTestId('realtime-chat-connect'));
    const socket = lastSocket();
    act(() => { socket.open(); });

    act(() => { socket.serverFrame({ type: 'join', from: 'system', text: 'ada joined alpha', room: 'alpha', at: 't', seq: 1 }); });
    const log = screen.getByTestId('realtime-chat-log');
    expect(log).toHaveTextContent('[join] system: ada joined alpha');

    fireEvent.change(screen.getByTestId('realtime-chat-draft'), { target: { value: 'hello room' } });
    fireEvent.click(screen.getByTestId('realtime-chat-send'));
    expect(socket.sent).toEqual(['{"text":"hello room"}']);
    expect(screen.getByTestId('realtime-chat-draft')).toHaveValue('');

    act(() => { socket.serverFrame({ type: 'say', from: 'bob', text: 'hello back', room: 'alpha', at: 't', seq: 2 }); });
    expect(log).toHaveTextContent('[say] bob: hello back');

    act(() => { socket.serverFrame({ type: 'leave', from: 'system', text: 'bob left alpha', room: 'alpha', at: 't', seq: 3 }); });
    expect(log).toHaveTextContent('[leave] system: bob left alpha');
  });

  it('stays closed after a drop — reconnect is client discipline, not the socket\'s', () => {
    stubStateFetch([
      { name: 'alpha', sessions: 0 },
      { name: 'beta', sessions: 0 },
    ]);
    vi.stubGlobal('WebSocket', FakeWebSocket);

    render(<ChatDemo />);
    fireEvent.click(screen.getByTestId('realtime-chat-connect'));
    const socket = lastSocket();
    act(() => { socket.open(); });

    act(() => { socket.close(); });
    expect(screen.getByTestId('realtime-chat-connection')).toHaveTextContent('closed');
    expect(socket.readyState).toBe(3);
  });
});

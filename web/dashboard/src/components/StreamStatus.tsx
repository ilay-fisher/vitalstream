import type { Connection } from '../lib/alertsReducer';

const TEXT: Record<Connection, string> = {
  connecting: 'Connecting to live alerts',
  open: 'Live alerts connected',
  reconnecting: 'Live alerts reconnecting',
};

export function StreamStatus({ connection }: { connection: Connection }) {
  return (
    <span className={`stream stream-${connection}`} role="status">
      <span className="dot" aria-hidden="true" />
      {TEXT[connection]}
    </span>
  );
}

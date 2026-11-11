import { useEffect, useState } from 'react';
import { AlertFeed } from './components/AlertFeed';
import { CopilotPanel } from './components/CopilotPanel';
import { PatientDetail } from './components/PatientDetail';
import { WardOverview } from './components/WardOverview';
import { useTheme } from './hooks/useTheme';

type Route = { name: 'ward' } | { name: 'alerts' } | { name: 'patient'; id: string };

export function parseRoute(hash: string): Route {
  const path = hash.replace(/^#/, '') || '/';
  const patient = /^\/patients\/([^/]+)$/.exec(path);
  if (patient) return { name: 'patient', id: decodeURIComponent(patient[1]) };
  if (path === '/alerts') return { name: 'alerts' };
  return { name: 'ward' };
}

export function App() {
  const [route, setRoute] = useState<Route>(() => parseRoute(window.location.hash));
  const [copilot, setCopilot] = useState(false);
  const { theme, toggle } = useTheme();

  useEffect(() => {
    const onHash = () => setRoute(parseRoute(window.location.hash));
    window.addEventListener('hashchange', onHash);
    return () => window.removeEventListener('hashchange', onHash);
  }, []);

  return (
    <div className={`shell${copilot ? ' with-copilot' : ''}`}>
      <a className="skip" href="#main">
        Skip to content
      </a>
      <header className="topbar">
        <a className="brand" href="#/" aria-label="VitalStream, ward overview">
          <svg viewBox="0 0 28 16" width="28" height="16" aria-hidden="true">
            <path
              d="M1 8h6l3-6 4 12 3-6h10"
              fill="none"
              stroke="currentColor"
              strokeWidth="2"
              strokeLinecap="round"
              strokeLinejoin="round"
            />
          </svg>
          <span>VitalStream</span>
        </a>
        <nav aria-label="Main">
          <a
            href="#/"
            aria-current={route.name === 'ward' || route.name === 'patient' ? 'page' : undefined}
          >
            Ward
          </a>
          <a href="#/alerts" aria-current={route.name === 'alerts' ? 'page' : undefined}>
            Alerts
          </a>
        </nav>
        <div className="top-actions">
          <button
            onClick={toggle}
            aria-label={`Switch to ${theme === 'light' ? 'dark' : 'light'} mode`}
          >
            {theme === 'light' ? 'Dark' : 'Light'}
          </button>
          <button className="primary" aria-expanded={copilot} onClick={() => setCopilot((v) => !v)}>
            Copilot
          </button>
        </div>
      </header>
      <main id="main" tabIndex={-1}>
        {route.name === 'ward' && <WardOverview />}
        {route.name === 'alerts' && <AlertFeed />}
        {route.name === 'patient' && <PatientDetail key={route.id} id={route.id} />}
      </main>
      {copilot && (
        <CopilotPanel
          patientId={route.name === 'patient' ? route.id : undefined}
          onClose={() => setCopilot(false)}
        />
      )}
    </div>
  );
}

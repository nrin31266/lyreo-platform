import { useEffect, useState } from 'react';
import { Link, Navigate, Route, Routes, useLocation, useNavigate } from 'react-router-dom';
import { lyreoBrand } from '@lyreo/design-system';
import { userManager } from '../auth';
import { AiSettings } from './AiSettings';
import { Jobs } from './Jobs';
import { LessonBuilder } from './LessonBuilder';
import { Overview } from './Overview';

function Callback() {
  const navigate = useNavigate();

  useEffect(() => {
    userManager.signinRedirectCallback().then(() => navigate('/'));
  }, [navigate]);

  return <div className="center">Completing sign-in…</div>;
}

function Login() {
  return (
    <div className="login">
      <div className="brand-mark">〰</div>
      <h1>{lyreoBrand.name}</h1>
      <p>Admin workspace · {lyreoBrand.tagline}</p>
      <button onClick={() => void userManager.signinRedirect()}>Sign in with Keycloak</button>
    </div>
  );
}

function Shell() {
  const location = useLocation();
  const [authenticated, setAuthenticated] = useState<boolean | null>(null);

  useEffect(() => {
    userManager.getUser().then(user => setAuthenticated(Boolean(user && !user.expired)));
  }, []);

  if (authenticated === null) return <div className="center">Loading Lyreo…</div>;
  if (!authenticated) return <Login />;

  const links = [
    ['/', 'Overview'],
    ['/lessons/new', 'Lesson Builder'],
    ['/jobs', 'Background Jobs'],
    ['/settings/ai', 'AI Providers'],
    ['/curriculum', 'Curriculum'],
    ['/lexicon', 'Lexicon'],
  ] as const;

  return (
    <div className="shell">
      <aside>
        <div className="logo">
          <span>◒</span>
          <div>
            <strong>Lyreo</strong>
            <small>Admin</small>
          </div>
        </div>

        <nav>
          {links.map(([to, label]) => (
            <Link className={location.pathname === to ? 'active' : ''} to={to} key={to}>
              {label}
            </Link>
          ))}
        </nav>

        <button className="ghost" onClick={() => void userManager.signoutRedirect()}>
          Sign out
        </button>
      </aside>

      <main>
        <Routes>
          <Route path="/" element={<Overview />} />
          <Route path="/lessons/new" element={<LessonBuilder />} />
          <Route path="/jobs" element={<Jobs />} />
          <Route path="/settings/ai" element={<AiSettings />} />
          <Route
            path="/curriculum"
            element={(
              <Placeholder
                title="Curriculum"
                text="Build learning paths from references to Lesson, Grammar, TOEIC and future content types."
              />
            )}
          />
          <Route
            path="/lexicon"
            element={(
              <Placeholder
                title="Lexicon"
                text="Global dictionary with provenance, missing-Vietnamese maintenance and lazy enrichment."
              />
            )}
          />
          <Route path="*" element={<Navigate to="/" />} />
        </Routes>
      </main>
    </div>
  );
}

function Placeholder({ title, text }: { title: string; text: string }) {
  return (
    <section>
      <div className="eyebrow">Lyreo workspace</div>
      <h1>{title}</h1>
      <div className="card">
        <p>{text}</p>
        <p className="muted">
          This boundary is intentional; full domain-specific CRUD belongs to a later implementation
          slice rather than a fake starter screen.
        </p>
      </div>
    </section>
  );
}

export function App() {
  return (
    <Routes>
      <Route path="/auth/callback" element={<Callback />} />
      <Route path="/*" element={<Shell />} />
    </Routes>
  );
}

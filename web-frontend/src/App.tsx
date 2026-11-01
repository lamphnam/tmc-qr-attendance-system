import { NavLink, Route, Routes } from 'react-router-dom';
import { AREAS } from './catalog';
import Dashboard from './pages/Dashboard';
import DevSlice from './pages/DevSlice';
import NotImplemented from './pages/NotImplemented';

export default function App() {
  return (
    <div className="shell">
      <header className="topbar">
        <strong>TMC Attendance</strong>
        <span className="badge badge-warn">Development scaffold</span>
        <nav>
          <NavLink to="/" end>
            Status
          </NavLink>
          {AREAS.map((area) => (
            <NavLink key={area.path} to={area.path}>
              {area.title}
            </NavLink>
          ))}
          <NavLink to="/dev/slice">Dev slice</NavLink>
        </nav>
      </header>
      <main>
        <Routes>
          <Route path="/" element={<Dashboard />} />
          {AREAS.map((area) => (
            <Route key={area.path} path={area.path} element={<NotImplemented area={area} />} />
          ))}
          <Route path="/dev/slice" element={<DevSlice />} />
          <Route path="*" element={<p>Unknown page.</p>} />
        </Routes>
      </main>
    </div>
  );
}

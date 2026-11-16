import { NavLink, Outlet, Route, Routes } from 'react-router-dom';
import { AREAS } from './catalog';
import { DEMO_PATH } from './demo/config';
import Dashboard from './pages/Dashboard';
import DevSlice from './pages/DevSlice';
import MainFlowDemo from './pages/MainFlowDemo';
import NotImplemented from './pages/NotImplemented';

function ScaffoldLayout() {
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
          <NavLink to={DEMO_PATH}>Main flow demo</NavLink>
        </nav>
      </header>
      <main>
        <Outlet />
      </main>
    </div>
  );
}

export default function App() {
  return (
    <Routes>
      <Route path={DEMO_PATH} element={<MainFlowDemo />} />
      <Route element={<ScaffoldLayout />}>
        <Route path="/" element={<Dashboard />} />
        {AREAS.map((area) => (
          <Route key={area.path} path={area.path} element={<NotImplemented area={area} />} />
        ))}
        <Route path="/dev/slice" element={<DevSlice />} />
        <Route path="*" element={<p>Unknown page.</p>} />
      </Route>
    </Routes>
  );
}

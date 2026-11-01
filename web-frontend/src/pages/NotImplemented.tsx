import type { AreaDef } from '../catalog';

export default function NotImplemented({ area }: { area: AreaDef }) {
  return (
    <section>
      <h1>
        {area.title} <span className="badge badge-bad">Not implemented</span>
      </h1>
      <p>
        This area has no working UI yet. Nothing on this page is real data, and there is deliberately no fake login or
        mock report here.
      </p>
      <dl>
        <dt>Owner</dt>
        <dd>{area.owner}</dd>
        <dt>Requirements</dt>
        <dd>{area.requirements}</dd>
        <dt>Needs first</dt>
        <dd>
          <ul>
            {area.needs.map((need) => (
              <li key={need}>{need}</li>
            ))}
          </ul>
        </dd>
      </dl>
      <p className="muted">See docs/TODO.md for what to build first.</p>
    </section>
  );
}

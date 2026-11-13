import { toneFor, type Tone } from '../../demo/format';

export default function Badge({ children, tone, className }: { children: string; tone?: Tone; className?: string }) {
  const extra = className ? ` ${className}` : '';
  return <span className={`tmc-badge tmc-badge-${tone ?? toneFor(children)}${extra}`}>{children}</span>;
}

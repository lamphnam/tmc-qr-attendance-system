import { useEffect } from 'react';

export function usePolling(task: () => Promise<void>, intervalMs: number): void {
  useEffect(() => {
    let running = false;
    let stopped = false;
    const tick = async () => {
      if (running || stopped) return;
      running = true;
      try {
        await task();
      } finally {
        running = false;
      }
    };
    void tick();
    const timer = setInterval(() => void tick(), intervalMs);
    return () => {
      stopped = true;
      clearInterval(timer);
    };
  }, [task, intervalMs]);
}

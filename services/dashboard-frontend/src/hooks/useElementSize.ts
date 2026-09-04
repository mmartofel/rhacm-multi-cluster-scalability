import { useRef, useEffect, useState, RefObject } from 'react';

export function useElementSize<T extends HTMLElement>(
  initial: { width: number; height: number } = { width: 560, height: 210 }
): [RefObject<T>, { width: number; height: number }] {
  const ref = useRef<T>(null);
  const [size, setSize] = useState(initial);

  useEffect(() => {
    if (!ref.current) return;
    const ro = new ResizeObserver(entries => {
      const { width, height } = entries[0].contentRect;
      setSize({ width: Math.floor(width) - 2, height: Math.floor(height) });
    });
    ro.observe(ref.current);
    return () => ro.disconnect();
  }, []);

  return [ref, size];
}

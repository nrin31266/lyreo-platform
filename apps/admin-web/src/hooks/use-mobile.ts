import { useEffect, useState } from 'react';
const DESKTOP_QUERY = '(min-width: 768px)';
export function useIsMobile() {
  const [isMobile, setIsMobile] = useState(
    () => !window.matchMedia(DESKTOP_QUERY).matches,
  );
  useEffect(() => {
    const media = window.matchMedia(DESKTOP_QUERY);
    const update = () => setIsMobile(!media.matches);
    media.addEventListener('change', update);
    update();
    return () => media.removeEventListener('change', update);
  }, []);
  return isMobile;
}

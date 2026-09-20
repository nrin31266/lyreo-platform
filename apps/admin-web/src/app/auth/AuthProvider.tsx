import type { User } from 'oidc-client-ts';
import {
  createContext,
  useContext,
  useEffect,
  useMemo,
  useState,
  type PropsWithChildren,
} from 'react';
import { userManager } from '@/lib/oidc/client';
import { isAdminUser } from './roles';

export type AuthContextValue = {
  user: User | null;
  isLoading: boolean;
  isAuthenticated: boolean;
  isAdmin: boolean;
  login: () => Promise<void>;
  logout: () => Promise<void>;
};

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: PropsWithChildren) {
  const [user, setUser] = useState<User | null>(null);
  const [isLoading, setIsLoading] = useState(true);

  useEffect(() => {
    let mounted = true;

    userManager
      .getUser()
      .then(loadedUser => {
        if (!mounted) return;
        setUser(loadedUser && !loadedUser.expired ? loadedUser : null);
        setIsLoading(false);
      })
      .catch(() => {
        if (!mounted) return;
        setUser(null);
        setIsLoading(false);
      });

    const onUserLoaded = (loadedUser: User) => {
      if (!mounted) return;
      setUser(loadedUser && !loadedUser.expired ? loadedUser : null);
    };

    const onUserUnloaded = () => {
      if (!mounted) return;
      setUser(null);
    };

    userManager.events.addUserLoaded(onUserLoaded);
    userManager.events.addUserUnloaded(onUserUnloaded);

    return () => {
      mounted = false;
      userManager.events.removeUserLoaded(onUserLoaded);
      userManager.events.removeUserUnloaded(onUserUnloaded);
    };
  }, []);

  const value = useMemo<AuthContextValue>(() => {
    const isAuthenticated = Boolean(user && !user.expired);
    const isAdmin = isAuthenticated && isAdminUser(user);
    return {
      user,
      isLoading,
      isAuthenticated,
      isAdmin,
      login: () => userManager.signinRedirect(),
      logout: () => userManager.signoutRedirect(),
    };
  }, [user, isLoading]);

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error('useAuth must be used within an AuthProvider');
  }
  return context;
}

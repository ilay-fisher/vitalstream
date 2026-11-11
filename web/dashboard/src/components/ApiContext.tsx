import { createContext, useContext, type ReactNode } from 'react';
import { createApi, type Api } from '../lib/api';

const defaultApi = createApi();
const ApiContext = createContext<Api>(defaultApi);

export function ApiProvider({ api, children }: { api?: Api; children: ReactNode }) {
  return <ApiContext.Provider value={api ?? defaultApi}>{children}</ApiContext.Provider>;
}

export function useApi(): Api {
  return useContext(ApiContext);
}

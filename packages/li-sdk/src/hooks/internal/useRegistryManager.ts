import { useLIContext } from './useLIContext';

export const useRegistryManager = () => {
  const context = useLIContext();

  if (!context) {
    throw new Error(`useRegistryManager must be used within a LIContext.Provider`);
  }

  return context.registryManager;
};
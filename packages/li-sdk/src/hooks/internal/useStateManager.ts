import { useLIContext } from './useLIContext';

export const useStateManager = () => {
  const context = useLIContext();

  if (!context) {
    throw new Error(`useStateManager must be used within a LIContext.Provider`);
  }

  return context.stateManager;
};
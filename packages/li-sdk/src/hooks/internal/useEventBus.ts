import { useLIContext } from './useLIContext';

export const useEventBus = () => {
  const context = useLIContext();

  if (!context) {
    throw new Error(`useEventBus must be used within a LIContext.Provider`);
  }

  return context.eventBus;
};
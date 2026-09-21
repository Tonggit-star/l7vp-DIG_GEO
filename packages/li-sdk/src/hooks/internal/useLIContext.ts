import type EventEmitter from '@antv/event-emitter';
import React from 'react';
import type RegistryManager from '../../registry';
import type StateManager from '../../state';

export type LIContextValue = {
  stateManager: StateManager;
  registryManager: RegistryManager;
  eventBus: EventEmitter;
};

export const LIContext = React.createContext<LIContextValue | null>(null);

export const useLIContext = () => {
  const context = React.useContext(LIContext);
  return context;
};

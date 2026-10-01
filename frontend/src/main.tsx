import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { App } from './app/App'
import { installUnhandledRejectionHandler } from './app/unhandledRejection'
import './index.css'

// Promise rejections nobody caught surface as an error toast instead of failing silently.
installUnhandledRejectionHandler()

const container = document.getElementById('root')
if (!container) throw new Error('Root element #root not found')

createRoot(container).render(
  <StrictMode>
    <App />
  </StrictMode>,
)

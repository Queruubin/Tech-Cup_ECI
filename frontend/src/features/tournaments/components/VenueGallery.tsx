import type { ReactNode } from 'react'
import { useFileUrl } from '@/lib/files'
import type { VenueResponse } from '@/types/api'

function VenueImage({ fileId, name }: { fileId: string | null; name: string }) {
  const { url } = useFileUrl(fileId)
  return (
    <div className="flex h-40 w-full items-center justify-center overflow-hidden bg-stone-100 text-stone-300">
      {url ? (
        <img src={url} alt={name} className="h-full w-full object-cover" />
      ) : (
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" className="h-10 w-10" aria-hidden="true">
          <rect x="3" y="5" width="18" height="14" rx="2" />
          <path d="M3 12h18M12 5v14M12 12m-2.5 0a2.5 2.5 0 105 0 2.5 2.5 0 10-5 0" />
        </svg>
      )}
    </div>
  )
}

export interface VenueGalleryProps {
  venues: VenueResponse[]
  renderActions?: (venue: VenueResponse) => ReactNode
}

export function VenueGallery({ venues, renderActions }: VenueGalleryProps) {
  if (venues.length === 0) return <p className="text-sm text-stone-500">Aún no se han registrado canchas.</p>
  return (
    <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
      {venues.map((venue) => (
        <article key={venue.id} className="overflow-hidden rounded-2xl border border-stone-200 bg-white shadow-sm">
          <VenueImage fileId={venue.imageFileId} name={venue.name} />
          <div className="flex items-start justify-between gap-2 p-4">
            <div className="min-w-0">
              <h4 className="truncate text-sm font-semibold text-ink">{venue.name}</h4>
              {venue.description && <p className="mt-0.5 text-xs text-stone-600">{venue.description}</p>}
            </div>
            {renderActions && <div className="shrink-0">{renderActions(venue)}</div>}
          </div>
        </article>
      ))}
    </div>
  )
}

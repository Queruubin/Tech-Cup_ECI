import { cn } from '@/lib/cn'
import { useFileUrl } from '@/lib/files'
import { initials } from '@/lib/format'

export interface TeamPhotoProps {
  name: string
  /** GridFS id of the team photo; the team's initials are shown without one. */
  photoFileId?: string | null
  size?: 'sm' | 'md' | 'lg'
  className?: string
}

const SIZE_CLASSES = {
  sm: 'h-10 w-10 rounded-lg text-sm',
  md: 'h-14 w-14 rounded-xl text-base',
  lg: 'h-20 w-20 rounded-2xl text-2xl',
} as const

/** The team photo, or its initials as a fallback (also while the photo loads or if it fails). */
export function TeamPhoto({ name, photoFileId, size = 'md', className }: TeamPhotoProps) {
  const { url } = useFileUrl(photoFileId)
  return (
    <span
      role="img"
      aria-label={url ? `Foto de ${name}` : `Iniciales de ${name}`}
      className={cn(
        'inline-flex shrink-0 select-none items-center justify-center overflow-hidden border border-stone-200 bg-brand-50 font-semibold text-brand-700',
        SIZE_CLASSES[size],
        className,
      )}
    >
      {url ? <img src={url} alt="" className="h-full w-full object-cover" /> : initials(name) || '?'}
    </span>
  )
}

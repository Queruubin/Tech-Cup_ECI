import { cn } from '@/lib/cn'
import { useFileUrl } from '@/lib/files'
import { initials } from '@/lib/format'

export interface AvatarProps {
  name: string
  /** GridFS file id served by `GET /api/files/{id}`. */
  photoFileId?: string | null
  size?: 'sm' | 'md' | 'lg' | 'xl'
  className?: string
}

const SIZE_CLASSES = {
  sm: 'h-8 w-8 text-xs',
  md: 'h-10 w-10 text-sm',
  lg: 'h-14 w-14 text-base',
  xl: 'h-24 w-24 text-2xl',
} as const

export function Avatar({ name, photoFileId, size = 'md', className }: AvatarProps) {
  const { url } = useFileUrl(photoFileId)
  return (
    <span
      className={cn(
        'inline-flex shrink-0 select-none items-center justify-center overflow-hidden rounded-full bg-brand-100 font-semibold text-brand-800',
        SIZE_CLASSES[size],
        className,
      )}
      aria-label={name}
      role="img"
    >
      {url ? <img src={url} alt={name} className="h-full w-full object-cover" /> : initials(name) || '?'}
    </span>
  )
}

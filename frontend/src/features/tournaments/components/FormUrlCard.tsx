import { useState } from 'react'
import { Button } from '@/components/atoms/Button'
import { Input } from '@/components/atoms/Input'
import { Card } from '@/components/molecules/Card'
import { FormField } from '@/components/molecules/FormField'
import { ConfirmDialog } from '@/components/molecules/Modal'
import { errorMessage } from '@/lib/api'
import { useMutation } from '@/lib/useQuery'
import { toast } from '@/store/ui.store'
import type { TournamentResponse } from '@/types/api'
import { tournamentsApi } from '../api'
import { FORM_URL_MAX_LENGTH, canEditFormUrl, formUrlError, safeFormUrl } from '../formUrl'

export interface FormUrlCardProps {
  tournament: TournamentResponse
  onUpdated: (tournament: TournamentResponse) => void
}

/**
 * ADMIN or ORGANIZER: the link to the university ("Escuela") form captains must fill in before
 * registering (they then upload the evidence with the registration). Set, replaced or removed while
 * the tournament is not suspended nor finished, like the server allows.
 */
export function FormUrlCard({ tournament, onUpdated }: FormUrlCardProps) {
  const current = tournament.formUrl
  const link = safeFormUrl(current)
  const editable = canEditFormUrl(tournament.status)
  const [value, setValue] = useState(current ?? '')
  // The client-side message appears once the organizer tried to save, not while typing the first characters.
  const [submitted, setSubmitted] = useState(false)
  const [confirmRemove, setConfirmRemove] = useState(false)

  const save = useMutation(async (url: string) => {
    const updated = await tournamentsApi.setFormUrl(tournament.id, url)
    onUpdated(updated)
    return updated
  })
  const remove = useMutation(async () => {
    const updated = await tournamentsApi.removeFormUrl(tournament.id)
    onUpdated(updated)
    return updated
  })

  const clientError = submitted ? formUrlError(value) : null
  const unchanged = value.trim() === (current ?? '')

  const handleSave = () => {
    setSubmitted(true)
    if (formUrlError(value) !== null) return
    save
      .mutate(value.trim())
      .then((updated) => {
        toast.success(current ? 'URL del formulario actualizada.' : 'URL del formulario guardada.')
        setValue(updated.formUrl ?? '')
        setSubmitted(false)
      })
      .catch(() => undefined)
  }

  const handleRemove = () =>
    remove
      .mutate()
      .then(() => {
        toast.success('URL del formulario eliminada.')
        setValue('')
        setSubmitted(false)
      })
      .catch((cause: unknown) => toast.error(errorMessage(cause, 'No fue posible quitar la URL del formulario.')))
      .finally(() => setConfirmRemove(false))

  return (
    <Card
      title="Formulario de la Escuela"
      description="Enlace al formulario que los capitanes deben diligenciar antes de inscribirse; luego adjuntan la evidencia en la inscripción."
    >
      <div className="flex flex-col gap-3">
        {link ? (
          <p className="text-sm text-stone-700">
            Enlace actual:{' '}
            <a href={link} target="_blank" rel="noopener noreferrer" className="break-all font-medium text-brand-700 underline">
              {link}
            </a>
          </p>
        ) : current ? (
          <p className="text-sm text-amber-700">La URL guardada no es válida y no se muestra como enlace. Reemplácela.</p>
        ) : (
          <p className="text-sm text-stone-500">Sin URL del formulario</p>
        )}

        {editable ? (
          <>
            <FormField
              label="URL del formulario"
              hint="Debe empezar por http:// o https://."
              error={clientError ?? save.error}
            >
              <Input
                type="url"
                inputMode="url"
                placeholder="https://"
                maxLength={FORM_URL_MAX_LENGTH}
                value={value}
                onChange={(event) => setValue(event.target.value)}
              />
            </FormField>
            <div className="flex flex-wrap gap-2">
              <Button size="sm" variant="outline" disabled={unchanged} loading={save.loading} onClick={handleSave}>
                {current ? 'Cambiar URL' : 'Guardar URL'}
              </Button>
              {current && (
                <Button size="sm" variant="danger" onClick={() => setConfirmRemove(true)}>
                  Quitar URL
                </Button>
              )}
            </div>
          </>
        ) : (
          <p className="text-xs text-stone-500">
            {tournament.status === 'SUSPENDED'
              ? 'El torneo está suspendido; no se puede cambiar la URL del formulario.'
              : 'El torneo finalizó; no se puede cambiar la URL del formulario.'}
          </p>
        )}
      </div>

      <ConfirmDialog
        open={confirmRemove}
        title="Quitar URL del formulario"
        description="Los capitanes dejarán de ver el enlace al formulario de la Escuela. La evidencia seguirá siendo obligatoria para inscribirse."
        confirmLabel="Quitar"
        danger
        loading={remove.loading}
        onConfirm={handleRemove}
        onCancel={() => setConfirmRemove(false)}
      />
    </Card>
  )
}

import { classNames } from 'utils'

export default function SubmitButton({
  disabled,
  onClick,
  caption,
  error
}: {
  disabled: boolean
  onClick: () => void
  caption: () => string
  error: string | null | undefined
}) {
  return (
    <>
      <button
        type="button"
        disabled={disabled}
        className={classNames(
          'mt-4 w-full inline-flex justify-center rounded-md border border-lightBackground px-4 py-2 text-sm font-medium text-white focus:outline-none focus:ring-1 focus:ring-inset',
          disabled
            ? 'bg-neutralGray focus:ring-mutedGray'
            : 'bg-green hover:bg-brightGreen focus:ring-lightBackground'
        )}
        onClick={onClick}
      >
        {caption()}
      </button>

      {error && (
        <div className="mt-2 text-center text-sm text-brightRed">{error}</div>
      )}
    </>
  )
}

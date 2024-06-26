import {
  DepositStatus,
  OrderStatus,
  TradeSettlementStatus,
  WithdrawalStatus
} from 'apiClient'
import { classNames } from 'utils'
import Completed from 'assets/Completed.svg'
import Failed from 'assets/Failed.svg'
import Open from 'assets/Open.svg'
import Pending from 'assets/Pending.svg'
import Filled from 'assets/Filled.svg'

type UniversalStatus =
  | 'Pending'
  | 'Completed'
  | 'Failed'
  | 'Canceled'
  | 'Open'
  | 'Filled'
  | 'Partial'
  | 'Expired'

export function Status({
  status
}: {
  status: TradeSettlementStatus | OrderStatus | DepositStatus | WithdrawalStatus
}) {
  function toUniversalStatus(): UniversalStatus {
    switch (status) {
      case 'Pending':
      case 'Sequenced':
      case 'Settling':
        return 'Pending'
      case 'Complete':
      case 'Completed':
        return 'Completed'
      case 'Failed':
      case 'FailedSettling':
      case 'Rejected':
      case 'Cancelled':
        return 'Canceled'
      case 'Open':
        return 'Open'
      case 'Filled':
        return 'Filled'
      case 'Partial':
        return 'Partial'
      case 'Expired':
        return 'Expired'
    }
  }

  const universalStatus = toUniversalStatus()

  function textColor() {
    switch (universalStatus) {
      case 'Pending':
      case 'Partial':
        return 'text-statusOrange'
      case 'Open':
        return 'text-statusYellow'
      case 'Failed':
      case 'Canceled':
      case 'Expired':
        return 'text-statusRed'
      case 'Completed':
        return 'text-statusGreen'
      case 'Filled':
        return 'text-statusBlue'
    }
  }

  function icon() {
    switch (universalStatus) {
      case 'Pending':
      case 'Partial':
        return Pending
      case 'Open':
        return Open
      case 'Failed':
      case 'Canceled':
      case 'Expired':
        return Failed
      case 'Completed':
        return Completed
      case 'Filled':
        return Filled
    }
  }

  return (
    <>
      <span
        className={classNames(
          textColor(),
          'font-medium',
          'bg-darkBluishGray8 px-2 narrow:px-4 py-2 rounded whitespace-nowrap flex w-fit'
        )}
      >
        <img className="inline" src={icon()} alt={universalStatus} />
        <span className="ml-2 hidden narrow:inline">{universalStatus}</span>
      </span>
    </>
  )
}

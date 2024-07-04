import React from 'react'
import { LastMilestone } from 'apiClient'

export function MilestoneReachedWidget({
  milestone
}: {
  milestone: LastMilestone
}) {
  return (
    <div className="flex flex-col items-center justify-center px-5 text-center font-semibold text-white">
      <div>
        <span className="text-2xl">🎉</span> New Milestone:{' '}
        {milestone.invites === -1
          ? 'Unlimited Invites'
          : `+${milestone.invites} Invite${milestone.invites === 1 ? '' : 's'}`}
      </div>
    </div>
  )
}
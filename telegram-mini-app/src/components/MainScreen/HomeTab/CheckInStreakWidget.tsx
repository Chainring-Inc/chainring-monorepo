import React from 'react'
import { CheckInStreak } from 'apiClient'

export function CheckInStreakWidget({ streak }: { streak: CheckInStreak }) {
  const daysLabel = streak.days === 1 ? 'day' : 'days'
  const ticketsLabel = streak.gameTickets === 1 ? 'Game Ticket' : 'Game Tickets'

  return (
    <div className="flex flex-col items-center justify-center px-5 py-4 text-center text-lg font-semibold text-white">
      <div>
        <span className="text-2xl">🔥</span> Check-in streak: {streak.days}{' '}
        {daysLabel}
      </div>
      <div>+{streak.reward.toFixed(0)} CR</div>
      <div>
        +{streak.gameTickets} {ticketsLabel}
      </div>
    </div>
  )
}

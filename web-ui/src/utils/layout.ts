import { useState, useEffect } from 'react'

function minWidthByBreakpoint(breakpoint: string): number {
  return (
    {
      sm: 640,
      md: 768,
      lg: 1024,
      xl: 1280,
      '2xl': 1536
    }[breakpoint] ?? 0
  )
}

const columnsByMinWidth: { minWidth: number; columns: number }[] = [
  { minWidth: 0, columns: 1 },
  { minWidth: 640, columns: 1 },
  { minWidth: 768, columns: 1 },
  { minWidth: 1024, columns: 2 },
  { minWidth: 1280, columns: 3 },
  { minWidth: 1536, columns: 3 }
]

export type WindowDimensions = { width: number; height: number }
export function getColumnsForWidth(width: number): number {
  for (let i = 0; i < columnsByMinWidth.length; i++) {
    const entry = columnsByMinWidth[i]
    if (width < entry.minWidth) {
      return entry.columns
    }
  }
  return 3
}

export function gridClasses() {
  function gridAtBreakpoint(breakpoint: string): string {
    return `${breakpoint}:grid-cols-${getColumnsForWidth(
      minWidthByBreakpoint(breakpoint)
    )}`
  }
  return [
    `grid-cols-${getColumnsForWidth(0)}`,
    gridAtBreakpoint('sm'),
    gridAtBreakpoint('md'),
    gridAtBreakpoint('lg'),
    gridAtBreakpoint('xl'),
    gridAtBreakpoint('2xl')
  ]
}

export function widgetSize(width: number): number {
  const columns = getColumnsForWidth(width)
  return width / columns - (columns + 1) * 12
}

function getWindowDimensions(): WindowDimensions {
  const { innerWidth: width, innerHeight: height } = window
  return {
    width,
    height
  }
}

export function useWindowDimensions() {
  const [windowDimensions, setWindowDimensions] = useState(
    getWindowDimensions()
  )

  useEffect(() => {
    function handleResize() {
      setWindowDimensions(getWindowDimensions())
    }

    window.addEventListener('resize', handleResize)
    return () => window.removeEventListener('resize', handleResize)
  }, [])

  return windowDimensions
}
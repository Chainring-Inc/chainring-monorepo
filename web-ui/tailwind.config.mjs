/** @type {import('tailwindcss').Config} */
const defaultTheme = require('tailwindcss/defaultTheme')

export default {
  content: ['./src/**/*.{mjs,js,ts,jsx,tsx}'],
  theme: {
    screens: {
      'sm': '750px',
      'md': '1000px',
      'lg': '1500px',
      'xl': '1750px',
      '2xl': '2250px',
    },
    extend: {
      fontFamily: {
        sans: ['"Roboto Mono"', ...defaultTheme.fontFamily.sans]
      }
    },
    colors: {
      darkBluishGray10: "#1A1B21",
      darkBluishGray9: "#1B222B",
      darkBluishGray8: "#1F2A39",
      darkBluishGray7: "#313D4E",
      darkBluishGray6: "#444F5F",
      darkBluishGray4: "#6F7885",
      darkBluishGray3: "#89919D",
      darkBluishGray2: "#A0A6B1",
      darkBluishGray1: "#BABEC5",
      primary4: "#FF8718",
      primary5: "#E4720B",
      olhcGreen: "#39CF63",
      olhcRed: "#FF5A50",

      // legacy
      lightBackground: "#B5CBCD",
      darkBackground: "#66B0B6",
      neutralGray: "#8C8C8C",
      mutedGray: "#555555",
      darkGray: "#3F3F3F",
      white: "#FFF",
      green: "#10A327",
      brightGreen: "#1cd23a",
      red: "#7F1D1D",
      brightRed: "#a12222",
      black: "#111",
    }
  },
  plugins: [
    require('@tailwindcss/forms')
  ],
  safelist: [
    {
      pattern: /grid-cols-/,
      variants: ['sm', 'md', 'lg', 'xl', '2xl']
    },
    {
      pattern: /col-span-/,
    }
  ]
}

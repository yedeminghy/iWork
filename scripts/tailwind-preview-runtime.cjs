const postcss = require('postcss');
const tailwindcss = require('tailwindcss');

module.exports = async function compileTailwindPreview(content) {
  const result = await postcss([
    tailwindcss({
      content: [{ raw: content, extension: 'html' }]
    })
  ]).process('@tailwind base;\n@tailwind components;\n@tailwind utilities;', { from: undefined });
  return result.css;
};
